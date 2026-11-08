package com.seekehr.netdiagnostics.core.monitor;

import com.seekehr.netdiagnostics.core.quality.QualityEngine;
import com.seekehr.netdiagnostics.core.stats.RttStats;
import com.seekehr.netdiagnostics.core.stats.SampleRing;
import com.seekehr.netdiagnostics.core.stats.StatSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * State of the live connection monitor. Single-threaded: all methods must be called on the
 * Minecraft client thread (packet observations are handed over via a queue by the caller).
 *
 * <p>Series kept:</p>
 * <ul>
 *   <li><b>Minecraft-reported latency</b>: the value the server publishes in the tab list. The server
 *       computes it from keepalive round trips and smooths it ({@code (3·old + new) / 4} in vanilla);
 *       vanilla servers refresh it about every 30 s. It is application-level and lags real changes.
 *       A sample is recorded when an update is observed (value change, or a latency-update packet
 *       when packet instrumentation is enabled).</li>
 *   <li><b>Keepalive spacing</b> (instrumentation only): time between consecutive server keepalive
 *       packets as decoded by the client. Variation reflects network delay variation plus server tick
 *       timing; it is not RTT.</li>
 *   <li><b>Keepalive turnaround</b> (instrumentation only): time from a keepalive being decoded to the
 *       client's reply being handed to the encoder; measures local client handling, not network.</li>
 * </ul>
 */
public final class LatencyMonitor {
    public static final String METRIC_REPORTED = "Minecraft-reported latency";

    public static final class QualitySnapshot {
        public final long time;
        public final int samples;
        public final double median;
        public final double jitter;
        public final int spikes;
        /** 0 = good, 1 = fair, 2 = poor, -1 = no data. */
        public final int grade;

        QualitySnapshot(long time, int samples, double median, double jitter, int spikes, int grade) {
            this.time = time;
            this.samples = samples;
            this.median = median;
            this.jitter = jitter;
            this.spikes = spikes;
            this.grade = grade;
        }
    }

    private final QualityEngine quality;
    private SampleRing reported;
    private final SampleRing keepAliveSpacing = new SampleRing(1024);
    private final SampleRing keepAliveTurnaround = new SampleRing(256);
    private final List<QualitySnapshot> qualityHistory = new ArrayList<QualitySnapshot>();
    private static final int MAX_QUALITY_HISTORY = 120;

    private boolean active;
    private String serverName;
    private String remoteAddress;
    private long sessionStart;
    private long monitorStart;
    private int disconnects;
    private int keepAliveGaps;
    private long lastKeepAliveArrival;
    private int lastReported = -1;
    private long lastReportedChange;
    private long lastQualitySnapshot;
    private int spikesSinceSnapshot;
    private int version;

    public LatencyMonitor(QualityEngine quality, int historyMinutes, int intervalMs) {
        this.quality = quality;
        resize(historyMinutes, intervalMs);
    }

    /** Re-allocates the history buffer (clears it). Capacity = history duration / interval, min 128. */
    public void resize(int historyMinutes, int intervalMs) {
        int cap = (int) Math.max(128, Math.min(20000, historyMinutes * 60_000L / Math.max(250, intervalMs)));
        if (reported == null || reported.capacity() != cap) {
            reported = new SampleRing(cap);
            version++;
        }
    }

    public int version() {
        return version;
    }

    public boolean active() {
        return active;
    }

    public void start(long now) {
        if (!active) {
            active = true;
            monitorStart = now;
            lastQualitySnapshot = now;
            version++;
        }
    }

    public void stop() {
        active = false;
        version++;
    }

    public void onConnected(long now, String server, String remote) {
        serverName = server;
        remoteAddress = remote;
        sessionStart = now;
        lastReported = -1;
        lastKeepAliveArrival = 0;
        quality.resetSeries(METRIC_REPORTED);
        version++;
    }

    public void onDisconnected(long now, boolean unexpected, String reason) {
        if (sessionStart > 0) {
            disconnects++;
            quality.onDisconnect(now, serverName == null ? "server" : serverName, now - sessionStart, unexpected, reason);
        }
        sessionStart = 0;
        lastKeepAliveArrival = 0;
        version++;
    }

    /**
     * Feed the current Minecraft-reported latency. {@code updateObserved} is true when a latency update
     * packet was seen; otherwise a sample is only recorded when the value changes.
     */
    public void onReportedLatency(long now, int latencyMs, boolean updateObserved) {
        if (!active || latencyMs < 0) {
            return;
        }
        if (!updateObserved && latencyMs == lastReported) {
            return;
        }
        if (latencyMs == 0 && lastReported < 0) {
            return; // 0 = not yet measured by the server
        }
        lastReported = latencyMs;
        lastReportedChange = now;
        boolean spike = quality.onLatencySample(now, METRIC_REPORTED, latencyMs);
        if (spike) {
            spikesSinceSnapshot++;
        }
        reported.add(now, latencyMs, spike ? SampleRing.FLAG_SPIKE : SampleRing.FLAG_NONE);
        version++;
    }

    public void onKeepAliveArrival(long now) {
        if (!active) {
            return;
        }
        if (lastKeepAliveArrival > 0) {
            double spacing = now - lastKeepAliveArrival;
            if (keepAliveSpacing.size() >= 5) {
                double typical = RttStats.median(lastValues(keepAliveSpacing, 20));
                if (typical > 0 && spacing > Math.max(typical * 2.5, typical + 3000)) {
                    keepAliveGaps++;
                    quality.onKeepAliveGap(now, spacing, typical);
                }
            }
            keepAliveSpacing.add(now, spacing);
        }
        lastKeepAliveArrival = now;
        version++;
    }

    public void onKeepAliveTurnaround(long now, double ms) {
        if (active) {
            keepAliveTurnaround.add(now, ms);
        }
    }

    /** Call periodically; records a quality snapshot once per minute. */
    public void tick(long now) {
        if (!active || now - lastQualitySnapshot < 60_000L) {
            return;
        }
        StatSummary s = summary(now - 60_000L);
        int grade = s.isEmpty() ? -1 : (spikesSinceSnapshot >= 3 || s.jitter > 25 ? 2
                : (spikesSinceSnapshot >= 1 || s.jitter > 10 ? 1 : 0));
        qualityHistory.add(new QualitySnapshot(now, s.count, s.median, s.jitter, spikesSinceSnapshot, grade));
        while (qualityHistory.size() > MAX_QUALITY_HISTORY) {
            qualityHistory.remove(0);
        }
        spikesSinceSnapshot = 0;
        lastQualitySnapshot = now;
        version++;
    }

    private static double[] lastValues(SampleRing r, int k) {
        int n = Math.min(k, r.size());
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = r.value(r.size() - n + i);
        }
        return out;
    }

    public StatSummary summary(long sinceMillis) {
        double[] buf = new double[reported.size()];
        int n = reported.copyValuesSince(sinceMillis, buf);
        return RttStats.summarize(buf, n);
    }

    public StatSummary keepAliveSpacingSummary() {
        return RttStats.summarize(keepAliveSpacing.toArray());
    }

    public StatSummary keepAliveTurnaroundSummary() {
        return RttStats.summarize(keepAliveTurnaround.toArray());
    }

    public SampleRing reported() {
        return reported;
    }

    public List<QualitySnapshot> qualityHistory() {
        return qualityHistory;
    }

    public String serverName() {
        return serverName;
    }

    public String remoteAddress() {
        return remoteAddress;
    }

    public long sessionStart() {
        return sessionStart;
    }

    public long monitorStart() {
        return monitorStart;
    }

    public int disconnects() {
        return disconnects;
    }

    public int keepAliveGaps() {
        return keepAliveGaps;
    }

    public int lastReported() {
        return lastReported;
    }

    public long lastReportedChange() {
        return lastReportedChange;
    }

    public void clearHistory() {
        reported.clear();
        keepAliveSpacing.clear();
        keepAliveTurnaround.clear();
        qualityHistory.clear();
        disconnects = 0;
        keepAliveGaps = 0;
        quality.resetSeries(METRIC_REPORTED);
        version++;
    }

    public Map<String, Object> toMap(long now) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("active", active);
        m.put("server", serverName);
        m.put("remoteAddress", remoteAddress);
        m.put("sessionDurationMs", sessionStart > 0 ? now - sessionStart : 0);
        m.put("disconnects", disconnects);
        m.put("keepAliveGaps", keepAliveGaps);
        m.put("metric", METRIC_REPORTED + " (server-computed, smoothed, application-level; not TCP RTT)");
        m.put("jitterDefinition", "mean absolute difference between consecutive samples");
        m.put("summary", com.seekehr.netdiagnostics.core.test.TestRun.summaryMap(summary(0)));
        m.put("keepAliveSpacing", com.seekehr.netdiagnostics.core.test.TestRun.summaryMap(keepAliveSpacingSummary()));
        m.put("keepAliveTurnaround",
                com.seekehr.netdiagnostics.core.test.TestRun.summaryMap(keepAliveTurnaroundSummary()));
        List<Object> samples = new ArrayList<Object>();
        for (int i = 0; i < reported.size(); i++) {
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("t", reported.time(i));
            s.put("ms", reported.value(i));
            s.put("spike", reported.flag(i) == SampleRing.FLAG_SPIKE);
            samples.add(s);
        }
        m.put("samples", samples);
        return m;
    }
}
