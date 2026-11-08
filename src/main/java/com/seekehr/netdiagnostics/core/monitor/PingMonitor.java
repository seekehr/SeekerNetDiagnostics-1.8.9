package com.seekehr.netdiagnostics.core.monitor;

import com.seekehr.netdiagnostics.core.dns.DnsInfo;
import com.seekehr.netdiagnostics.core.dns.DnsResolver;
import com.seekehr.netdiagnostics.core.mc.McProtocol;
import com.seekehr.netdiagnostics.core.mc.McStatusClient;
import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.stats.RttStats;
import com.seekehr.netdiagnostics.core.stats.StatSummary;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Continuously pings one Minecraft server from the game process (one server-list ping per interval,
 * each on a fresh connection) and keeps a rolling buffer of samples. Never joins the server.
 */
public final class PingMonitor {
    public static final int MIN_INTERVAL_MS = 500;
    public static final int[] INTERVALS = {500, 1000, 2000, 5000};
    public static final int CAPACITY = 3600;
    public static final int TIMEOUT_MS = 5000;
    /** A TCP connect to a public IP faster than this was answered by a local proxy, not the server. */
    public static final double LOCAL_PROXY_MS = 1.5;

    /** Supplies the tab-list ping for the same server, or -1. */
    public interface TabPing {
        int get();
    }

    public static final class Sample {
        public final long time;
        public final ProbeOutcome outcome;
        /** Ping packet → pong, ms. NaN unless SUCCESS. */
        public final double pingMs;
        public final double connectMs;
        public final int tabPingMs;
        public final String error;

        Sample(long time, ProbeOutcome outcome, double pingMs, double connectMs, int tabPingMs, String error) {
            this.time = time;
            this.outcome = outcome;
            this.pingMs = outcome == ProbeOutcome.SUCCESS ? pingMs : Double.NaN;
            this.connectMs = connectMs;
            this.tabPingMs = tabPingMs;
            this.error = error;
        }

        public boolean ok() {
            return outcome == ProbeOutcome.SUCCESS;
        }
    }

    /** Immutable view of the buffer and its statistics. */
    public static final class Snapshot {
        public final List<Sample> samples;
        public final StatSummary ping;
        public final StatSummary connect;
        public final int failures;
        public final int spikes;

        Snapshot(List<Sample> samples) {
            this.samples = samples;
            List<Double> p = new ArrayList<Double>();
            List<Double> c = new ArrayList<Double>();
            int f = 0;
            for (Sample s : samples) {
                if (s.ok()) {
                    p.add(s.pingMs);
                } else {
                    f++;
                }
                if (!Double.isNaN(s.connectMs)) {
                    c.add(s.connectMs);
                }
            }
            ping = RttStats.summarize(toArray(p));
            connect = RttStats.summarize(toArray(c));
            failures = f;
            int sp = 0;
            if (!ping.isEmpty()) {
                double limit = ping.median * 1.5 + 20;
                for (Sample s : samples) {
                    if (s.ok() && s.pingMs > limit) {
                        sp++;
                    }
                }
            }
            spikes = sp;
        }

        public Sample last() {
            return samples.isEmpty() ? null : samples.get(samples.size() - 1);
        }

        public double lossPercent() {
            return samples.isEmpty() ? 0 : 100.0 * failures / samples.size();
        }

        private static double[] toArray(List<Double> v) {
            double[] out = new double[v.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = v.get(i);
            }
            return out;
        }
    }

    public final Target target;
    private final TabPing tabPing;
    private final AtomicBoolean stop = new AtomicBoolean();
    private final ArrayDeque<Sample> buffer = new ArrayDeque<Sample>();
    private volatile int version;
    private Snapshot cached;
    private int cachedVersion = -1;
    private Thread thread;

    public volatile int intervalMs = 1000;
    public volatile boolean paused;
    public volatile String status = "Starting";
    public final long startedAt = System.currentTimeMillis();

    // Route facts, filled in by the worker.
    public volatile String srvRecord;
    public volatile String connectHost;
    public volatile int connectPort;
    public volatile DnsInfo dns;
    public volatile String address;
    public volatile String probeLocalAddress;
    public volatile String probeInterface;
    public volatile McProtocol.StatusInfo serverInfo;

    public PingMonitor(Target target, TabPing tabPing) {
        this.target = target;
        this.tabPing = tabPing;
        this.connectHost = target.host;
        this.connectPort = target.port;
    }

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "SeekerNetDiagnostics-monitor");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    public void stop() {
        stop.set(true);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean stopped() {
        return stop.get();
    }

    public void reset() {
        synchronized (buffer) {
            buffer.clear();
            version++;
        }
    }

    public void cycleInterval() {
        for (int i = 0; i < INTERVALS.length; i++) {
            if (INTERVALS[i] == intervalMs) {
                intervalMs = INTERVALS[(i + 1) % INTERVALS.length];
                return;
            }
        }
        intervalMs = 1000;
    }

    /** Cached snapshot; recomputed only after a new sample arrives. */
    public Snapshot snapshot() {
        synchronized (buffer) {
            if (cached == null || cachedVersion != version) {
                cached = new Snapshot(new ArrayList<Sample>(buffer));
                cachedVersion = version;
            }
            return cached;
        }
    }

    private void add(Sample s) {
        synchronized (buffer) {
            buffer.addLast(s);
            while (buffer.size() > CAPACITY) {
                buffer.removeFirst();
            }
            version++;
        }
    }

    // ------------------------------------------------------------------ worker

    private void loop() {
        try {
            InetSocketAddress remote = resolve();
            if (remote == null) {
                return;
            }
            while (!stop.get()) {
                long t0 = System.currentTimeMillis();
                if (paused) {
                    status = "Paused";
                } else {
                    status = "Running";
                    probe(remote);
                }
                long wait = Math.max(MIN_INTERVAL_MS, intervalMs) - (System.currentTimeMillis() - t0);
                if (wait > 0) {
                    Thread.sleep(wait);
                }
            }
        } catch (InterruptedException ignored) {
            // stopped
        } finally {
            status = stop.get() ? "Stopped" : status;
        }
    }

    private InetSocketAddress resolve() {
        status = "Resolving " + target.host;
        String host = target.host;
        int port = target.port;
        if (!target.portExplicit && !DnsResolver.isIpLiteral(host)) {
            DnsResolver.Srv srv = DnsResolver.lookupMinecraftSrv(host);
            if (srv != null) {
                srvRecord = "_minecraft._tcp." + host + " -> " + srv.target + ":" + srv.port;
                host = srv.target;
                port = srv.port;
            }
        }
        connectHost = host;
        connectPort = port;
        DnsInfo d = DnsResolver.resolve(host, true);
        dns = d;
        if (!d.resolved()) {
            status = d.error != null ? d.error : "DNS resolution failed";
            stop.set(true);
            return null;
        }
        try {
            InetAddress a = InetAddress.getByName(d.addresses.get(0)); // literal, no lookup
            address = a.getHostAddress();
            return new InetSocketAddress(a, port);
        } catch (Exception e) {
            status = "Could not use resolved address: " + e.getMessage();
            stop.set(true);
            return null;
        }
    }

    private void probe(InetSocketAddress remote) {
        McStatusClient.Result r = McStatusClient.ping(remote, connectHost, TIMEOUT_MS,
                McStatusClient.DEFAULT_MAX_RESPONSE_BYTES, stop);
        ProbeOutcome o = r.outcome();
        if (o == ProbeOutcome.CANCELLED || stop.get()) {
            return;
        }
        if (r.status != null) {
            serverInfo = r.status;
        }
        if (r.localAddress != null) {
            String local = r.localAddress.getHostAddress();
            if (!local.equals(probeLocalAddress)) {
                probeLocalAddress = local;
                probeInterface = interfaceName(r.localAddress);
            }
        }
        add(new Sample(System.currentTimeMillis(), o, r.pingPongMs, r.tcpConnectMs, tabPing.get(),
                o == ProbeOutcome.SUCCESS ? null : r.error));
    }

    /** Display name of the network adapter that owns {@code local}, or null. */
    public static String interfaceName(InetAddress local) {
        try {
            NetworkInterface ni = NetworkInterface.getByInetAddress(local);
            if (ni == null) {
                return null;
            }
            String n = ni.getDisplayName();
            return n != null ? n : ni.getName();
        } catch (Exception e) {
            return null;
        }
    }

    /** True when probe connections complete faster than any remote server could answer. */
    public boolean localProxyDetected(Snapshot s) {
        String a = address;
        return !s.connect.isEmpty() && s.connect.median < LOCAL_PROXY_MS && a != null
                && !DnsResolver.isLocalOrPrivate(a);
    }
}
