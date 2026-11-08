package com.seekehr.netdiagnostics.core.test;

import com.seekehr.netdiagnostics.core.config.Settings;
import com.seekehr.netdiagnostics.core.dns.DnsInfo;
import com.seekehr.netdiagnostics.core.dns.DnsMessage;
import com.seekehr.netdiagnostics.core.dns.DnsResolver;
import com.seekehr.netdiagnostics.core.mc.McStatusClient;
import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.core.net.TcpProbe;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs diagnostic jobs on a single daemon worker thread (bounded queue), so at most one connection
 * attempt is in flight at any time. A per-destination limiter enforces at least
 * {@link Settings#MIN_INTERVAL_MS} between attempts to the same IP and port across all jobs.
 */
public final class TestEngine {
    public static final int MAX_QUEUED_JOBS = 4;

    public interface Listener {
        /** Called on the worker thread whenever a run changes (attempt added, phase change, finished). */
        void onUpdate(TestRun run);
    }

    private final ThreadPoolExecutor executor;
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final List<TestRun> active = new CopyOnWriteArrayList<TestRun>();
    private final Map<String, Long> lastAttemptNanos = new HashMap<String, Long>();
    private volatile boolean shutdown;

    public TestEngine() {
        executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(MAX_QUEUED_JOBS), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "SeekerNetDiagnostics-worker");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        });
        executor.allowCoreThreadTimeOut(true);
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public List<TestRun> activeRuns() {
        return new ArrayList<TestRun>(active);
    }

    public boolean busy() {
        return !active.isEmpty();
    }

    /**
     * Queue a run. Throws IllegalStateException with a user-facing message when the queue is full or
     * the engine is shutting down.
     */
    public TestRun submit(final TestRun run, final Settings settings) {
        if (shutdown) {
            throw new IllegalStateException("Diagnostics are shutting down.");
        }
        active.add(run);
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        execute(run, settings);
                    } catch (Throwable t) {
                        run.error = "Internal error: " + t;
                        run.state = TestRun.State.FAILED;
                    } finally {
                        if (run.finishedAt == 0) {
                            run.finishedAt = System.currentTimeMillis();
                        }
                        active.remove(run);
                        fire(run);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            active.remove(run);
            throw new IllegalStateException("Too many tests queued (max " + MAX_QUEUED_JOBS
                    + "). Wait for the current test to finish or cancel it.");
        }
        fire(run);
        return run;
    }

    public void cancelAll() {
        for (TestRun r : active) {
            r.cancelFlag.set(true);
        }
    }

    public void shutdown() {
        shutdown = true;
        cancelAll();
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void fire(TestRun run) {
        for (Listener l : listeners) {
            try {
                l.onUpdate(run);
            } catch (RuntimeException ignored) {
                // a listener must never break the engine
            }
        }
    }

    // ------------------------------------------------------------------ execution

    void execute(TestRun run, Settings settings) {
        run.startedAt = System.currentTimeMillis();
        run.state = TestRun.State.RUNNING;
        run.phase = "Resolving " + run.target.host;
        fire(run);

        DnsResolver resolver = new DnsResolver(Math.min(2000, run.tcpTimeoutMs));
        String connectHost = run.target.host;
        int connectPort = run.target.port;
        String srvNote = null;
        if (run.kind == TestRun.Kind.FULL && !run.target.portExplicit && !DnsResolver.isIpLiteral(connectHost)) {
            DnsMessage.Record srv = resolver.lookupMinecraftSrv(connectHost);
            if (srv != null && srv.data != null && !srv.data.isEmpty() && srv.port > 0) {
                srvNote = "_minecraft._tcp." + run.target.host + " SRV → " + srv.data + ":" + srv.port
                        + (srv.ttl >= 0 ? " (TTL " + srv.ttl + "s)" : "");
                connectHost = srv.data.endsWith(".") ? srv.data.substring(0, srv.data.length() - 1) : srv.data;
                connectPort = srv.port;
            }
        }
        run.connectHost = connectHost;
        run.connectPort = connectPort;
        if (cancelled(run)) {
            return;
        }

        DnsInfo dns = resolver.resolve(connectHost, settings.preferIpv4);
        dns.srvRecord = srvNote;
        run.dns = dns;
        if (!dns.resolved()) {
            run.error = dns.error != null ? dns.error : "DNS resolution failed";
            run.addAttempt(new TestRun.Attempt(0, false, System.currentTimeMillis(), ProbeOutcome.DNS_FAILURE,
                    Double.NaN, dns.systemLookupMs, null, 0, null, connectPort, run.error));
            run.state = TestRun.State.FAILED;
            run.phase = "DNS resolution failed";
            run.finishedAt = System.currentTimeMillis();
            return;
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(dns.addresses.get(0)); // literal: no further lookup
        } catch (Exception e) {
            run.error = "Could not use resolved address: " + e.getMessage();
            run.state = TestRun.State.FAILED;
            return;
        }
        run.resolvedAddress = address.getHostAddress();
        InetSocketAddress remote = new InetSocketAddress(address, connectPort);
        fire(run);

        int total = run.warmupRequested + run.samplesRequested;
        for (int i = 0; i < total; i++) {
            if (cancelled(run)) {
                return;
            }
            boolean warm = i < run.warmupRequested;
            int index = warm ? -(run.warmupRequested - i) : i - run.warmupRequested + 1;
            run.phase = warm ? "Warm-up connection" : "TCP handshake " + index + " of " + run.samplesRequested;
            if (!waitForSlot(remote, run.intervalMs, run)) {
                return;
            }
            TcpProbe.Result r = TcpProbe.probe(remote, run.tcpTimeoutMs, run.cancelFlag);
            markAttempt(remote);
            if (r.outcome == ProbeOutcome.CANCELLED) {
                cancelled(run);
                return;
            }
            run.addAttempt(new TestRun.Attempt(index, warm, System.currentTimeMillis(), r.outcome, r.rttMs,
                    r.elapsedMs, r.localAddress, r.localPort, r.remoteAddress, r.remotePort, r.detail));
            fire(run);
        }

        if (run.kind == TestRun.Kind.FULL) {
            if (cancelled(run)) {
                return;
            }
            run.phase = "Minecraft status ping";
            fire(run);
            if (!waitForSlot(remote, run.intervalMs, run)) {
                return;
            }
            McStatusClient.Result st = McStatusClient.ping(remote, connectHost, run.statusTimeoutMs,
                    McStatusClient.DEFAULT_MAX_RESPONSE_BYTES, run.cancelFlag);
            markAttempt(remote);
            run.mcStatus = st;
        }
        run.phase = "Finished";
        run.state = TestRun.State.DONE;
        run.finishedAt = System.currentTimeMillis();
    }

    private boolean cancelled(TestRun run) {
        if (run.cancelFlag.get() || shutdown || Thread.currentThread().isInterrupted()) {
            run.state = TestRun.State.CANCELLED;
            run.phase = "Cancelled";
            run.finishedAt = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    private static String key(InetSocketAddress a) {
        return a.getAddress().getHostAddress() + "|" + a.getPort();
    }

    private void markAttempt(InetSocketAddress remote) {
        synchronized (lastAttemptNanos) {
            lastAttemptNanos.put(key(remote), System.nanoTime());
            if (lastAttemptNanos.size() > 256) {
                lastAttemptNanos.clear();
            }
        }
    }

    /** Sleeps (in cancellable slices) until the destination may be contacted again. */
    private boolean waitForSlot(InetSocketAddress remote, int intervalMs, TestRun run) {
        long minGap = Math.max(Settings.MIN_INTERVAL_MS, intervalMs) * 1_000_000L;
        while (true) {
            Long last;
            synchronized (lastAttemptNanos) {
                last = lastAttemptNanos.get(key(remote));
            }
            long wait = last == null ? 0 : (last + minGap) - System.nanoTime();
            if (wait <= 0) {
                return true;
            }
            if (cancelled(run)) {
                return false;
            }
            try {
                Thread.sleep(Math.max(1, Math.min(50, wait / 1_000_000L)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelled(run);
                return false;
            }
        }
    }
}
