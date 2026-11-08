package com.seekehr.netdiagnostics.core.quality;

import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.core.stats.RttStats;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.core.test.TestRun;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detects notable changes in measured latency. It reports what was measured and which metric
 * triggered the alert; it never attributes causes (ISP routing, congestion, server load, packet loss).
 *
 * <p>Rules for latency series (e.g. Minecraft-reported latency):</p>
 * <ul>
 *   <li><b>spike</b>: sample &gt; rolling baseline median (previous up to 30 samples, min 8) +
 *       max(50 ms, 3 × MAD).</li>
 *   <li><b>sudden-increase</b>: 3 spikes within the last 5 samples.</li>
 *   <li><b>frequent-spikes</b>: ≥ 5 spikes within the last 30 samples.</li>
 *   <li><b>sustained-degradation</b>: median of the last 10 samples exceeds the median of the 10
 *       before by ≥ max(20 ms, 20%).</li>
 *   <li><b>excessive-variation</b>: jitter over the last 20 samples ≥ max(15 ms, 25% of their median).</li>
 * </ul>
 * Each rule has a 60 s cooldown.
 */
public final class QualityEngine {
    public static final int MAX_ALERTS = 100;
    public static final long COOLDOWN_MS = 60_000L;
    public static final double SPIKE_MIN_MS = 50.0;

    private final LinkedList<Alert> alerts = new LinkedList<Alert>();
    private final Map<String, Long> lastFired = new HashMap<String, Long>();
    private final Map<String, Series> series = new HashMap<String, Series>();
    private volatile int version;

    private static final class Series {
        final double[] values = new double[30];
        final boolean[] spikes = new boolean[30];
        int n;
        int head;

        void add(double v, boolean spike) {
            values[head] = v;
            spikes[head] = spike;
            head = (head + 1) % values.length;
            if (n < values.length) {
                n++;
            }
        }

        /** Most recent k values, oldest first. */
        double[] last(int k) {
            k = Math.min(k, n);
            double[] out = new double[k];
            for (int i = 0; i < k; i++) {
                int idx = (head - k + i + values.length * 2) % values.length;
                out[i] = values[idx];
            }
            return out;
        }

        int spikesInLast(int k) {
            k = Math.min(k, n);
            int c = 0;
            for (int i = 0; i < k; i++) {
                if (spikes[(head - 1 - i + values.length * 2) % values.length]) {
                    c++;
                }
            }
            return c;
        }
    }

    public int version() {
        return version;
    }

    public synchronized List<Alert> alerts() {
        return new ArrayList<Alert>(alerts);
    }

    public synchronized void clear() {
        alerts.clear();
        lastFired.clear();
        series.clear();
        version++;
    }

    public synchronized void resetSeries(String metric) {
        series.remove(metric);
    }

    private void fire(long now, String rule, String metric, Alert.Severity sev, String msg) {
        String key = rule + "|" + metric;
        Long last = lastFired.get(key);
        if (last != null && now - last < COOLDOWN_MS) {
            return;
        }
        lastFired.put(key, now);
        alerts.addFirst(new Alert(now, rule, metric, sev, msg));
        while (alerts.size() > MAX_ALERTS) {
            alerts.removeLast();
        }
        version++;
    }

    /**
     * Feed one latency sample of the named metric (e.g. "Minecraft-reported latency").
     * Returns true when the sample is classified as a spike.
     */
    public synchronized boolean onLatencySample(long now, String metric, double ms) {
        Series s = series.get(metric);
        if (s == null) {
            s = new Series();
            series.put(metric, s);
        }
        boolean spike = false;
        double baseline = Double.NaN;
        if (s.n >= 8) {
            double[] prev = s.last(30);
            baseline = RttStats.median(prev);
            double mad = RttStats.mad(prev);
            double threshold = baseline + Math.max(SPIKE_MIN_MS, 3 * mad);
            spike = ms > threshold;
        }
        s.add(ms, spike);
        if (spike && s.spikesInLast(5) >= 3) {
            fire(now, "sudden-increase", metric, Alert.Severity.WARNING, String.format(Locale.ROOT,
                    "Three %s measurements in the last five exceeded the rolling baseline (%.0f ms) by %.0f ms or more "
                            + "(latest %.0f ms).", metric, baseline, SPIKE_MIN_MS, ms));
        }
        int spikes30 = s.spikesInLast(30);
        if (spike && spikes30 >= 5) {
            fire(now, "frequent-spikes", metric, Alert.Severity.WARNING, String.format(Locale.ROOT,
                    "Frequent spikes: %d of the last %d %s measurements were spikes above the rolling baseline.",
                    spikes30, Math.min(30, s.n), metric));
        }
        if (s.n >= 20) {
            double[] last20 = s.last(20);
            double[] older = new double[10];
            double[] newer = new double[10];
            System.arraycopy(last20, 0, older, 0, 10);
            System.arraycopy(last20, 10, newer, 0, 10);
            double mo = RttStats.median(older);
            double mn = RttStats.median(newer);
            if (mn - mo >= Math.max(20.0, mo * 0.20)) {
                fire(now, "sustained-degradation", metric, Alert.Severity.WARNING, String.format(Locale.ROOT,
                        "%s median increased from %.0f ms to %.0f ms across the last two 10-sample windows.",
                        capital(metric), mo, mn));
            }
            StatSummary w = RttStats.summarize(last20);
            if (w.jitter >= Math.max(15.0, w.median * 0.25)) {
                fire(now, "excessive-variation", metric, Alert.Severity.WARNING, String.format(Locale.ROOT,
                        "Excessive variation: %s jitter was %.1f ms over the last 20 samples (median %.0f ms).",
                        metric, w.jitter, w.median));
            }
        }
        return spike;
    }

    /** Evaluate a finished TCP test run, optionally against the previous run to the same target. */
    public synchronized void onTcpRun(TestRun run, TestRun previous) {
        long now = System.currentTimeMillis();
        String metric = "TCP handshake to " + run.target.display();
        int timeouts = run.count(ProbeOutcome.TIMEOUT);
        int measured = run.measuredAttempts();
        if (timeouts > 0) {
            fire(now, "tcp-timeouts", metric, Alert.Severity.WARNING, String.format(Locale.ROOT,
                    "%d of %d TCP handshakes to %s timed out. Timeouts alone do not prove packet loss.",
                    timeouts, measured, run.target.display()));
        }
        StatSummary cur = run.summary();
        if (previous != null && !cur.isEmpty() && !previous.summary().isEmpty()) {
            double a = previous.summary().median;
            double b = cur.median;
            if (b - a >= Math.max(10.0, a * 0.15)) {
                fire(now, "tcp-median-increase", metric, Alert.Severity.WARNING, String.format(Locale.ROOT,
                        "TCP handshake median increased from %.0f ms to %.0f ms across the last two testing windows "
                                + "(%s).", a, b, run.target.display()));
            }
        }
    }

    public synchronized void onKeepAliveGap(long now, double gapMs, double typicalMs) {
        fire(now, "keepalive-gap", "Keepalive arrival spacing", Alert.Severity.INFO, String.format(Locale.ROOT,
                "No server keepalive was observed for %.1f s (typical spacing %.1f s). This is an application-level "
                        + "observation and is not evidence of packet loss.", gapMs / 1000.0, typicalMs / 1000.0));
    }

    public synchronized void onDisconnect(long now, String server, long sessionMs, boolean unexpected, String reason) {
        String msg = String.format(Locale.ROOT, "Minecraft disconnect from %s after %s%s%s", server,
                duration(sessionMs), unexpected ? " (connection closed by the server or network)" : " (left by user)",
                reason == null || reason.isEmpty() ? "." : ": " + reason);
        lastFired.remove("disconnect|Minecraft connection"); // every disconnect is reported
        fire(now, "disconnect", "Minecraft connection", unexpected ? Alert.Severity.WARNING : Alert.Severity.INFO, msg);
    }

    public static String duration(long ms) {
        long s = ms / 1000;
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return (s / 60) + " min " + (s % 60) + " s";
        }
        return (s / 3600) + " h " + ((s % 3600) / 60) + " min";
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
