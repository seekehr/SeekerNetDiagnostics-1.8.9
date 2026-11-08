package com.seekehr.netdiagnostics.core.test;

import com.seekehr.netdiagnostics.core.dns.DnsInfo;
import com.seekehr.netdiagnostics.core.json.Json;
import com.seekehr.netdiagnostics.core.mc.McProtocol;
import com.seekehr.netdiagnostics.core.mc.McStatusClient;
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
 * One diagnostic test session against a target. Written by the worker thread, read by the GUI;
 * mutable collections are guarded by {@code this}.
 */
public final class TestRun {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis());

    public enum Kind {
        /** TCP handshake samples plus one Minecraft status ping. */
        FULL("TCP + Minecraft status"),
        /** TCP handshake samples only. */
        TCP("TCP handshake");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public enum State {
        QUEUED, RUNNING, DONE, CANCELLED, FAILED
    }

    public static final class Attempt {
        public final int index;
        public final boolean warmup;
        public final long timestamp;
        public final ProbeOutcome outcome;
        public final double rttMs;
        public final double elapsedMs;
        public final String localAddress;
        public final int localPort;
        public final String remoteAddress;
        public final int remotePort;
        public final String detail;

        public Attempt(int index, boolean warmup, long timestamp, ProbeOutcome outcome, double rttMs,
                       double elapsedMs, String localAddress, int localPort, String remoteAddress, int remotePort,
                       String detail) {
            this.index = index;
            this.warmup = warmup;
            this.timestamp = timestamp;
            this.outcome = outcome;
            this.rttMs = outcome == ProbeOutcome.SUCCESS ? rttMs : Double.NaN;
            this.elapsedMs = elapsedMs;
            this.localAddress = localAddress;
            this.localPort = localPort;
            this.remoteAddress = remoteAddress;
            this.remotePort = remotePort;
            this.detail = detail;
        }
    }

    public final long id;
    public final Kind kind;
    public final Target target;
    public final int samplesRequested;
    public final int warmupRequested;
    public final int intervalMs;
    public final int tcpTimeoutMs;
    public final int statusTimeoutMs;
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
    public volatile McStatusClient.Result mcStatus;
    public volatile String error;
    public volatile String phase = "Queued";

    private final List<Attempt> attempts = new ArrayList<Attempt>();
    private volatile StatSummary summary = StatSummary.EMPTY;

    public TestRun(Kind kind, Target target, int samples, int warmup, int intervalMs, int tcpTimeoutMs,
                   int statusTimeoutMs, String profileLabel, String exitLagDescription) {
        this(IDS.incrementAndGet(), kind, target, samples, warmup, intervalMs, tcpTimeoutMs, statusTimeoutMs,
                profileLabel, exitLagDescription);
    }

    private TestRun(long id, Kind kind, Target target, int samples, int warmup, int intervalMs, int tcpTimeoutMs,
                    int statusTimeoutMs, String profileLabel, String exitLagDescription) {
        this.id = id;
        this.kind = kind;
        this.target = target;
        this.samplesRequested = samples;
        this.warmupRequested = warmup;
        this.intervalMs = intervalMs;
        this.tcpTimeoutMs = tcpTimeoutMs;
        this.statusTimeoutMs = statusTimeoutMs;
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
        int n = 0;
        for (Attempt a : attempts) {
            if (!a.warmup && a.outcome == ProbeOutcome.SUCCESS) {
                n++;
            }
        }
        double[] out = new double[n];
        int i = 0;
        for (Attempt a : attempts) {
            if (!a.warmup && a.outcome == ProbeOutcome.SUCCESS) {
                out[i++] = a.rttMs;
            }
        }
        return out;
    }

    /** Successful, non-warm-up handshake times in measurement order. */
    public synchronized double[] successfulSamples() {
        return successfulSamplesLocked();
    }

    public StatSummary summary() {
        return summary;
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

    /** Fraction of measured attempts completed, 0..1. */
    public double progress() {
        if (finished()) {
            return 1.0;
        }
        int total = samplesRequested + warmupRequested + (kind == Kind.FULL ? 1 : 0);
        int done;
        synchronized (this) {
            done = attempts.size();
        }
        if (mcStatus != null) {
            done++;
        }
        return total == 0 ? 0 : Math.min(1.0, done / (double) total);
    }

    public long durationMs() {
        long end = finishedAt > 0 ? finishedAt : System.currentTimeMillis();
        return startedAt > 0 ? end - startedAt : 0;
    }

    // ------------------------------------------------------------------ serialisation

    public Map<String, Object> toMap(boolean includeLocalAddresses) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("kind", kind.name());
        m.put("testMethod", kind.label);
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
        m.put("tcpTimeoutMs", tcpTimeoutMs);
        m.put("statusTimeoutMs", statusTimeoutMs);
        m.put("profileLabel", profileLabel);
        m.put("exitLagSettingsDescription", exitLagDescription);
        m.put("origin", "Minecraft client JVM (in-process socket)");
        m.put("exitLagRouting", "not verified");
        m.put("error", error);
        m.put("dns", dns == null ? null : dns.toMap(includeLocalAddresses));
        StatSummary s = summary;
        m.put("summary", summaryMap(s));
        Map<String, Object> counts = new LinkedHashMap<String, Object>();
        for (Map.Entry<ProbeOutcome, Integer> e : outcomeCounts().entrySet()) {
            counts.put(e.getKey().name(), e.getValue());
        }
        m.put("failureCategories", counts);
        List<Object> list = new ArrayList<Object>();
        for (Attempt a : attempts()) {
            Map<String, Object> am = new LinkedHashMap<String, Object>();
            am.put("index", a.index);
            am.put("warmup", a.warmup);
            am.put("timestamp", a.timestamp);
            am.put("outcome", a.outcome.name());
            am.put("rttMs", a.rttMs);
            am.put("elapsedMs", a.elapsedMs);
            am.put("remote", a.remoteAddress);
            am.put("remotePort", a.remotePort);
            if (includeLocalAddresses) {
                am.put("localAddress", a.localAddress);
                am.put("localPort", a.localPort);
            }
            am.put("detail", a.detail);
            list.add(am);
        }
        m.put("attempts", list);
        McStatusClient.Result st = mcStatus;
        if (st != null) {
            Map<String, Object> sm = new LinkedHashMap<String, Object>();
            sm.put("handshakeHost", st.handshakeHost);
            sm.put("tcpOutcome", st.tcpOutcome.name());
            sm.put("tcpConnectMs", st.tcpConnectMs);
            sm.put("statusOk", st.statusOk);
            sm.put("statusResponseMs", st.statusResponseMs);
            sm.put("pingOk", st.pingOk);
            sm.put("pingPongMs", st.pingPongMs);
            sm.put("failedStage", st.failedStage == null ? null : st.failedStage.name());
            sm.put("error", st.error);
            sm.put("protocolNote", st.protocolNote);
            sm.put("responseBytes", st.responseBytes);
            if (st.status != null) {
                sm.put("versionName", st.status.versionName);
                sm.put("protocol", st.status.protocol);
                sm.put("onlinePlayers", st.status.onlinePlayers);
                sm.put("maxPlayers", st.status.maxPlayers);
                sm.put("motd", st.status.motd);
                sm.put("modType", st.status.modType);
            }
            m.put("minecraftStatus", sm);
        }
        return m;
    }

    public static Map<String, Object> summaryMap(StatSummary s) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("successfulSamples", s.count);
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

    /** Restores a run persisted with {@link #toMap(boolean)}. Returns null on malformed input. */
    public static TestRun fromMap(Map<String, Object> m) {
        try {
            Kind kind = Kind.valueOf(Json.str(m, "kind", "TCP"));
            Target t = new Target(Json.str(m, "host", "?"), (int) Json.num(m, "port", 25565),
                    Json.bool(m, "portExplicit", true));
            TestRun r = new TestRun((long) Json.num(m, "id", IDS.incrementAndGet()), kind, t,
                    (int) Json.num(m, "samplesRequested", 0), (int) Json.num(m, "warmupSamples", 0),
                    (int) Json.num(m, "intervalMs", 0), (int) Json.num(m, "tcpTimeoutMs", 0),
                    (int) Json.num(m, "statusTimeoutMs", 0), Json.str(m, "profileLabel", null),
                    Json.str(m, "exitLagSettingsDescription", null));
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
            List<Object> atts = Json.arr(m.get("attempts"));
            if (atts != null) {
                for (Object o : atts) {
                    Map<String, Object> am = Json.obj(o);
                    if (am == null) {
                        continue;
                    }
                    ProbeOutcome out = ProbeOutcome.valueOf(Json.str(am, "outcome", "UNKNOWN"));
                    double rtt = Json.num(am, "rttMs", Double.NaN);
                    if (out == ProbeOutcome.SUCCESS && (Double.isNaN(rtt) || rtt < 0)) {
                        out = ProbeOutcome.UNKNOWN;
                    }
                    r.addAttempt(new Attempt((int) Json.num(am, "index", 0), Json.bool(am, "warmup", false),
                            (long) Json.num(am, "timestamp", 0), out, rtt, Json.num(am, "elapsedMs", Double.NaN),
                            Json.str(am, "localAddress", null), (int) Json.num(am, "localPort", 0),
                            Json.str(am, "remote", null), (int) Json.num(am, "remotePort", 0),
                            Json.str(am, "detail", null)));
                }
            }
            Map<String, Object> dm = Json.obj(m.get("dns"));
            if (dm != null) {
                DnsInfo d = new DnsInfo();
                d.query = Json.str(dm, "query", null);
                d.ipLiteral = Json.bool(dm, "ipLiteral", false);
                d.systemLookupMs = Json.num(dm, "systemLookupMs", Double.NaN);
                d.srvRecord = Json.str(dm, "srvRecord", null);
                d.minTtlSeconds = (long) Json.num(dm, "minTtlSeconds", -1);
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
            Map<String, Object> sm = Json.obj(m.get("minecraftStatus"));
            if (sm != null) {
                McStatusClient.Result st = new McStatusClient.Result();
                st.handshakeHost = Json.str(sm, "handshakeHost", null);
                st.tcpOutcome = ProbeOutcome.valueOf(Json.str(sm, "tcpOutcome", "UNKNOWN"));
                st.tcpConnectMs = Json.num(sm, "tcpConnectMs", Double.NaN);
                st.statusOk = Json.bool(sm, "statusOk", false);
                st.statusResponseMs = Json.num(sm, "statusResponseMs", Double.NaN);
                st.pingOk = Json.bool(sm, "pingOk", false);
                st.pingPongMs = Json.num(sm, "pingPongMs", Double.NaN);
                st.error = Json.str(sm, "error", null);
                st.protocolNote = Json.str(sm, "protocolNote", null);
                String fs = Json.str(sm, "failedStage", null);
                st.failedStage = fs == null ? null : McStatusClient.Stage.valueOf(fs);
                if (st.statusOk) {
                    McProtocol.StatusInfo si = new McProtocol.StatusInfo();
                    si.versionName = Json.str(sm, "versionName", null);
                    si.protocol = (int) Json.num(sm, "protocol", -1);
                    si.onlinePlayers = (int) Json.num(sm, "onlinePlayers", -1);
                    si.maxPlayers = (int) Json.num(sm, "maxPlayers", -1);
                    si.motd = Json.str(sm, "motd", null);
                    si.modType = Json.str(sm, "modType", null);
                    st.status = si;
                }
                r.mcStatus = st;
            }
            r.phase = "Finished";
            return r;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
