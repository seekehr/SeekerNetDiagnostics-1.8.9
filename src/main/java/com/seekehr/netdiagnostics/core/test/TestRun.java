package com.seekehr.netdiagnostics.core.test;

import com.seekehr.netdiagnostics.core.dns.DnsInfo;
import com.seekehr.netdiagnostics.core.dns.DnsResolver;
import com.seekehr.netdiagnostics.core.json.Json;
import com.seekehr.netdiagnostics.core.mc.McProtocol;
import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.stats.RttStats;
import com.seekehr.netdiagnostics.core.stats.StatSummary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One test: repeated Minecraft server-list pings to a target. Each sample is a fresh connection
 * (handshake → status → ping/pong), so it travels the same path as the game's own connection,
 * including any process-level optimizer such as ExitLag.
 *
 * <p>Written by the worker thread, read by the GUI; mutable collections are guarded by {@code this}.</p>
 */
public final class TestRun {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis());

    /**
     * A TCP handshake to a public address that completes faster than this cannot have reached the
     * remote server; a local proxy accepted it.
     */
    public static final double LOCAL_INTERCEPT_MS = 1.5;

    public enum State {
        QUEUED, RUNNING, DONE, CANCELLED, FAILED
    }

    /** One sample. {@link #pingMs} is the measurement; the other timings are diagnostic. */
    public static final class Attempt {
        public final int index;
        public final boolean warmup;
        public final long timestamp;
        public final ProbeOutcome outcome;
        /** Ping packet sent → matching pong received, ms. NaN unless SUCCESS. */
        public final double pingMs;
        /** TCP connect time, ms (NaN if the connection failed). */
        public final double connectMs;
        /** Status request sent → status response received, ms (includes server processing). */
        public final double statusMs;
        public final String detail;

        public Attempt(int index, boolean warmup, long timestamp, ProbeOutcome outcome, double pingMs,
                       double connectMs, double statusMs, String detail) {
            this.index = index;
            this.warmup = warmup;
            this.timestamp = timestamp;
            this.outcome = outcome;
            this.pingMs = outcome == ProbeOutcome.SUCCESS ? pingMs : Double.NaN;
            this.connectMs = connectMs;
            this.statusMs = statusMs;
            this.detail = detail;
        }
    }

    public final long id;
    public final Target target;
    public final int samplesRequested;
    public final int warmupRequested;
    public final int intervalMs;
    public final int timeoutMs;
    public final String profileLabel;
    public final String exitLagDescription;
    public final AtomicBoolean cancelFlag = new AtomicBoolean();

    public volatile State state = State.QUEUED;
    public volatile long startedAt;
    public volatile long finishedAt;
    /** Host actually connected to (SRV target if one was used). */
    public volatile String connectHost;
    public volatile int connectPort;
    public volatile String resolvedAddress;
    public volatile DnsInfo dns;
    /** Server info from the most recent successful status response. */
    public volatile McProtocol.StatusInfo serverInfo;
    public volatile String protocolNote;
    public volatile String error;
    public volatile String phase = "Queued";

    private final List<Attempt> attempts = new ArrayList<Attempt>();
    private volatile StatSummary summary = StatSummary.EMPTY;

    public TestRun(Target target, int samples, int warmup, int intervalMs, int timeoutMs, String profileLabel,
                   String exitLagDescription) {
        this(IDS.incrementAndGet(), target, samples, warmup, intervalMs, timeoutMs, profileLabel, exitLagDescription);
    }

    private TestRun(long id, Target target, int samples, int warmup, int intervalMs, int timeoutMs,
                    String profileLabel, String exitLagDescription) {
        this.id = id;
        this.target = target;
        this.samplesRequested = samples;
        this.warmupRequested = warmup;
        this.intervalMs = intervalMs;
        this.timeoutMs = timeoutMs;
        this.profileLabel = profileLabel;
        this.exitLagDescription = exitLagDescription;
        this.connectHost = target.host;
        this.connectPort = target.port;
    }

    public synchronized void addAttempt(Attempt a) {
        attempts.add(a);
        if (!a.warmup && a.outcome == ProbeOutcome.SUCCESS) {
            summary = RttStats.summarize(successfulSamplesLocked());
        }
    }

    public synchronized List<Attempt> attempts() {
        return Collections.unmodifiableList(new ArrayList<Attempt>(attempts));
    }

    private double[] successfulSamplesLocked() {
        List<Double> v = new ArrayList<Double>();
        for (Attempt a : attempts) {
            if (!a.warmup && a.outcome == ProbeOutcome.SUCCESS) {
                v.add(a.pingMs);
            }
        }
        return toArray(v);
    }

    private static double[] toArray(List<Double> v) {
        double[] out = new double[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = v.get(i);
        }
        return out;
    }

    /** Successful, non-warm-up ping times in measurement order. */
    public synchronized double[] successfulSamples() {
        return successfulSamplesLocked();
    }

    /** Ping statistics (the headline measurement). */
    public StatSummary summary() {
        return summary;
    }

    /** TCP connect-time statistics over every measured sample that connected. */
    public synchronized StatSummary connectSummary() {
        List<Double> v = new ArrayList<Double>();
        for (Attempt a : attempts) {
            if (!a.warmup && !Double.isNaN(a.connectMs)) {
                v.add(a.connectMs);
            }
        }
        return RttStats.summarize(toArray(v));
    }

    /** Status-response statistics (application-level, includes server processing). */
    public synchronized StatSummary statusSummary() {
        List<Double> v = new ArrayList<Double>();
        for (Attempt a : attempts) {
            if (!a.warmup && !Double.isNaN(a.statusMs)) {
                v.add(a.statusMs);
            }
        }
        return RttStats.summarize(toArray(v));
    }

    /**
     * True when TCP connections to a public address completed faster than a remote server could
     * answer, i.e. a local proxy (such as ExitLag) accepted them. This shows interception, not which
     * route the proxy then used.
     */
    public boolean localInterceptDetected() {
        StatSummary c = connectSummary();
        return !c.isEmpty() && c.median < LOCAL_INTERCEPT_MS && resolvedAddress != null
                && !DnsResolver.isLocalOrPrivate(resolvedAddress);
    }

    public synchronized int measuredAttempts() {
        int n = 0;
        for (Attempt a : attempts) {
            if (!a.warmup) {
                n++;
            }
        }
        return n;
    }

    public synchronized int failedAttempts() {
        int n = 0;
        for (Attempt a : attempts) {
            if (!a.warmup && a.outcome != ProbeOutcome.SUCCESS && a.outcome != ProbeOutcome.CANCELLED) {
                n++;
            }
        }
        return n;
    }

    public synchronized Map<ProbeOutcome, Integer> outcomeCounts() {
        Map<ProbeOutcome, Integer> m = new EnumMap<ProbeOutcome, Integer>(ProbeOutcome.class);
        for (Attempt a : attempts) {
            if (!a.warmup) {
                Integer c = m.get(a.outcome);
                m.put(a.outcome, c == null ? 1 : c + 1);
            }
        }
        return m;
    }

    public int count(ProbeOutcome o) {
        Integer c = outcomeCounts().get(o);
        return c == null ? 0 : c;
    }

    public boolean finished() {
        State s = state;
        return s == State.DONE || s == State.CANCELLED || s == State.FAILED;
    }

    /** Fraction of samples completed, 0..1. */
    public double progress() {
        if (finished()) {
            return 1.0;
        }
        int total = samplesRequested + warmupRequested;
        int done;
        synchronized (this) {
            done = attempts.size();
        }
        return total == 0 ? 0 : Math.min(1.0, done / (double) total);
    }

    public long durationMs() {
        long end = finishedAt > 0 ? finishedAt : System.currentTimeMillis();
        return startedAt > 0 ? end - startedAt : 0;
    }

    // ------------------------------------------------------------------ serialisation

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("testMethod", "Minecraft server-list ping (status + ping/pong), new connection per sample");
        m.put("state", state.name());
        m.put("host", target.host);
        m.put("port", target.port);
        m.put("portExplicit", target.portExplicit);
        m.put("connectHost", connectHost);
        m.put("connectPort", connectPort);
        m.put("resolvedAddress", resolvedAddress);
        m.put("startedAt", startedAt);
        m.put("finishedAt", finishedAt);
        m.put("durationMs", durationMs());
        m.put("samplesRequested", samplesRequested);
        m.put("warmupSamples", warmupRequested);
        m.put("intervalMs", intervalMs);
        m.put("timeoutMs", timeoutMs);
        m.put("profileLabel", profileLabel);
        m.put("exitLagSettingsDescription", exitLagDescription);
        m.put("origin", "Minecraft client JVM (in-process socket)");
        m.put("localInterceptDetected", localInterceptDetected());
        m.put("exitLagRouting", "not verified");
        m.put("error", error);
        m.put("dns", dns == null ? null : dns.toMap());
        m.put("pingSummary", summaryMap(summary));
        m.put("connectSummary", summaryMap(connectSummary()));
        m.put("statusSummary", summaryMap(statusSummary()));
        Map<String, Object> counts = new LinkedHashMap<String, Object>();
        for (Map.Entry<ProbeOutcome, Integer> e : outcomeCounts().entrySet()) {
            counts.put(e.getKey().name(), e.getValue());
        }
        m.put("outcomes", counts);
        McProtocol.StatusInfo si = serverInfo;
        if (si != null) {
            Map<String, Object> sm = new LinkedHashMap<String, Object>();
            sm.put("versionName", si.versionName);
            sm.put("protocol", si.protocol);
            sm.put("onlinePlayers", si.onlinePlayers);
            sm.put("maxPlayers", si.maxPlayers);
            sm.put("motd", si.motd);
            m.put("server", sm);
        }
        m.put("protocolNote", protocolNote);
        List<Object> list = new ArrayList<Object>();
        for (Attempt a : attempts()) {
            Map<String, Object> am = new LinkedHashMap<String, Object>();
            am.put("index", a.index);
            am.put("warmup", a.warmup);
            am.put("timestamp", a.timestamp);
            am.put("outcome", a.outcome.name());
            am.put("pingMs", a.pingMs);
            am.put("connectMs", a.connectMs);
            am.put("statusMs", a.statusMs);
            am.put("detail", a.detail);
            list.add(am);
        }
        m.put("samples", list);
        return m;
    }

    public static Map<String, Object> summaryMap(StatSummary s) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("count", s.count);
        m.put("meanMs", s.mean);
        m.put("medianMs", s.median);
        m.put("minMs", s.min);
        m.put("maxMs", s.max);
        m.put("p95Ms", s.p95);
        m.put("p99Ms", s.p99);
        m.put("stdDevMs", s.stdDev);
        m.put("jitterMs", s.jitter);
        return m;
    }

    /** Restores a run saved with {@link #toMap()}. Returns null on malformed or older-format input. */
    public static TestRun fromMap(Map<String, Object> m) {
        try {
            List<Object> samples = Json.arr(m.get("samples"));
            if (samples == null) {
                return null; // pre-1.0 format (TCP handshake based): not comparable, skip
            }
            Target t = new Target(Json.str(m, "host", "?"), (int) Json.num(m, "port", 25565),
                    Json.bool(m, "portExplicit", true));
            TestRun r = new TestRun((long) Json.num(m, "id", IDS.incrementAndGet()), t,
                    (int) Json.num(m, "samplesRequested", 0), (int) Json.num(m, "warmupSamples", 0),
                    (int) Json.num(m, "intervalMs", 0), (int) Json.num(m, "timeoutMs", 0),
                    Json.str(m, "profileLabel", null), Json.str(m, "exitLagSettingsDescription", null));
            r.state = State.valueOf(Json.str(m, "state", "DONE"));
            if (r.state == State.RUNNING || r.state == State.QUEUED) {
                r.state = State.CANCELLED;
            }
            r.startedAt = (long) Json.num(m, "startedAt", 0);
            r.finishedAt = (long) Json.num(m, "finishedAt", 0);
            r.connectHost = Json.str(m, "connectHost", t.host);
            r.connectPort = (int) Json.num(m, "connectPort", t.port);
            r.resolvedAddress = Json.str(m, "resolvedAddress", null);
            r.error = Json.str(m, "error", null);
            r.protocolNote = Json.str(m, "protocolNote", null);
            for (Object o : samples) {
                Map<String, Object> am = Json.obj(o);
                if (am == null) {
                    continue;
                }
                ProbeOutcome out = ProbeOutcome.valueOf(Json.str(am, "outcome", "UNKNOWN"));
                double ping = Json.num(am, "pingMs", Double.NaN);
                if (out == ProbeOutcome.SUCCESS && (Double.isNaN(ping) || ping < 0)) {
                    out = ProbeOutcome.UNKNOWN;
                }
                r.addAttempt(new Attempt((int) Json.num(am, "index", 0), Json.bool(am, "warmup", false),
                        (long) Json.num(am, "timestamp", 0), out, ping, Json.num(am, "connectMs", Double.NaN),
                        Json.num(am, "statusMs", Double.NaN), Json.str(am, "detail", null)));
            }
            Map<String, Object> dm = Json.obj(m.get("dns"));
            if (dm != null) {
                DnsInfo d = new DnsInfo();
                d.query = Json.str(dm, "query", null);
                d.ipLiteral = Json.bool(dm, "ipLiteral", false);
                d.lookupMs = Json.num(dm, "lookupMs", Double.NaN);
                d.srvRecord = Json.str(dm, "srvRecord", null);
                d.error = Json.str(dm, "error", null);
                List<Object> addrs = Json.arr(dm.get("addresses"));
                if (addrs != null) {
                    for (Object a : addrs) {
                        if (a instanceof String) {
                            d.addresses.add((String) a);
                        }
                    }
                }
                r.dns = d;
            }
            Map<String, Object> sm = Json.obj(m.get("server"));
            if (sm != null) {
                McProtocol.StatusInfo si = new McProtocol.StatusInfo();
                si.versionName = Json.str(sm, "versionName", null);
                si.protocol = (int) Json.num(sm, "protocol", -1);
                si.onlinePlayers = (int) Json.num(sm, "onlinePlayers", -1);
                si.maxPlayers = (int) Json.num(sm, "maxPlayers", -1);
                si.motd = Json.str(sm, "motd", null);
                r.serverInfo = si;
            }
            r.phase = "Finished";
            return r;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
