package com.seekehr.netdiagnostics.core.quality;

import com.seekehr.netdiagnostics.core.compare.CompareSession;
import com.seekehr.netdiagnostics.core.monitor.LatencyMonitor;
import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.stats.SampleRing;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.core.test.TestRun;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class QualityAndMonitorTest {
    private static final String M = "Minecraft-reported latency";

    private static Alert find(QualityEngine q, String rule) {
        for (Alert a : q.alerts()) {
            if (a.rule.equals(rule)) {
                return a;
            }
        }
        return null;
    }

    @Test
    public void spikesTriggerSuddenIncreaseNamingMetric() {
        QualityEngine q = new QualityEngine();
        long t = 0;
        for (int i = 0; i < 12; i++) {
            assertFalse(q.onLatencySample(t += 1000, M, 50 + (i % 2)));
        }
        assertTrue(q.onLatencySample(t += 1000, M, 130));
        assertNull(find(q, "sudden-increase"));
        assertTrue(q.onLatencySample(t += 1000, M, 135));
        assertTrue(q.onLatencySample(t += 1000, M, 140));
        Alert a = find(q, "sudden-increase");
        assertTrue(a != null);
        assertEquals(M, a.metric);
        assertTrue(a.message, a.message.startsWith("Three Minecraft-reported latency measurements"));
        assertTrue(a.message.contains("by 50 ms or more"));
    }

    @Test
    public void smallVariationIsNotASpike() {
        QualityEngine q = new QualityEngine();
        long t = 0;
        for (int i = 0; i < 40; i++) {
            assertFalse(q.onLatencySample(t += 1000, M, 80 + (i % 5) * 4));
        }
        assertTrue(q.alerts().isEmpty());
    }

    @Test
    public void sustainedDegradation() {
        QualityEngine q = new QualityEngine();
        long t = 0;
        for (int i = 0; i < 10; i++) {
            q.onLatencySample(t += 1000, M, 60);
        }
        for (int i = 0; i < 10; i++) {
            q.onLatencySample(t += 1000, M, 100);
        }
        Alert a = find(q, "sustained-degradation");
        assertTrue(a != null);
        assertTrue(a.message, a.message.contains("increased from 60 ms to 100 ms"));
    }

    @Test
    public void tcpRunAlerts() {
        QualityEngine q = new QualityEngine();
        TestRun prev = run(97, 97, 97);
        TestRun cur = run(121, 121, 121);
        cur.addAttempt(new TestRun.Attempt(9, false, 1, ProbeOutcome.TIMEOUT, Double.NaN, 3000, null, 0, "x", 1, ""));
        q.onTcpRun(cur, prev);
        Alert inc = find(q, "tcp-median-increase");
        assertTrue(inc != null);
        assertTrue(inc.message, inc.message.startsWith("TCP handshake median increased from 97 ms to 121 ms across "
                + "the last two testing windows"));
        Alert to = find(q, "tcp-timeouts");
        assertTrue(to.message.contains("do not prove packet loss"));
    }

    @Test
    public void cooldownSuppressesRepeats() {
        QualityEngine q = new QualityEngine();
        TestRun r = run(10);
        r.addAttempt(new TestRun.Attempt(2, false, 1, ProbeOutcome.TIMEOUT, Double.NaN, 3000, null, 0, "x", 1, ""));
        q.onTcpRun(r, null);
        q.onTcpRun(r, null);
        assertEquals(1, q.alerts().size());
        q.onDisconnect(1000, "srv", 65_000, true, null);
        q.onDisconnect(2000, "srv", 5_000, false, null);
        assertEquals(3, q.alerts().size());
        assertTrue(q.alerts().get(1).message.contains("after 1 min 5 s"));
    }

    private static TestRun run(double... rtts) {
        TestRun r = new TestRun(TestRun.Kind.TCP, new Target("10.0.0.1", 25565, true), rtts.length, 0, 1000, 3000,
                3000, null, null);
        int i = 1;
        for (double v : rtts) {
            r.addAttempt(new TestRun.Attempt(i++, false, i, ProbeOutcome.SUCCESS, v, v, null, 0, "10.0.0.1", 25565, ""));
        }
        return r;
    }

    @Test
    public void monitorRecordsOnlyUpdates() {
        LatencyMonitor m = new LatencyMonitor(new QualityEngine(), 5, 1000);
        m.onConnected(0, "srv", "1.2.3.4:25565");
        m.onReportedLatency(1, 50, false); // inactive: ignored
        assertEquals(0, m.reported().size());
        m.start(0);
        m.onReportedLatency(1000, 0, false); // 0 before first measurement: ignored
        m.onReportedLatency(2000, 50, false);
        m.onReportedLatency(3000, 50, false); // unchanged poll: not a new sample
        m.onReportedLatency(4000, 50, true); // observed update packet: recorded
        m.onReportedLatency(5000, 70, false);
        SampleRing r = m.reported();
        assertEquals(3, r.size());
        StatSummary s = m.summary(0);
        assertEquals(50, s.min, 0);
        assertEquals(70, s.max, 0);
        assertEquals(10, s.jitter, 1e-9); // |50-50| + |70-50| over 2
        assertEquals(2, m.summary(3500).count);
    }

    @Test
    public void keepAliveGapsAreDetectedWithoutClaimingLoss() {
        QualityEngine q = new QualityEngine();
        LatencyMonitor m = new LatencyMonitor(q, 5, 1000);
        m.start(0);
        long t = 0;
        for (int i = 0; i < 8; i++) {
            m.onKeepAliveArrival(t += 2000);
        }
        assertEquals(0, m.keepAliveGaps());
        m.onKeepAliveArrival(t += 9000);
        assertEquals(1, m.keepAliveGaps());
        Alert a = q.alerts().get(0);
        assertEquals("keepalive-gap", a.rule);
        assertTrue(a.message.contains("not evidence of packet loss"));
        assertEquals(2000, m.keepAliveSpacingSummary().median, 1e-9);
    }

    @Test
    public void disconnectCountsAndQualitySnapshots() {
        QualityEngine q = new QualityEngine();
        LatencyMonitor m = new LatencyMonitor(q, 5, 1000);
        m.start(0);
        m.onConnected(1, "srv", null);
        for (int i = 1; i <= 30; i++) {
            m.onReportedLatency(i * 2000L, 40 + (i % 2), true);
        }
        m.tick(61_000);
        List<LatencyMonitor.QualitySnapshot> qh = m.qualityHistory();
        assertEquals(1, qh.size());
        assertEquals(0, qh.get(0).grade);
        m.onDisconnected(70_000, true, "disconnect screen shown");
        assertEquals(1, m.disconnects());
        assertEquals("disconnect", q.alerts().get(0).rule);
    }

    @Test
    public void compareSessionPoolsRunsAndSuggestsAbba() {
        CompareSession s = new CompareSession(new Target("h", 25565, true), 3);
        CompareSession.Profile a = s.addProfile("A", "auto");
        assertNull(s.suggestedNext());
        s.recordRun(a, run(10, 11, 12));
        CompareSession.Profile b = s.addProfile("B", "manual SG");
        s.recordRun(b, run(20, 21, 22));
        // two blocks done (A, B) -> ABBA continues with B then A
        assertEquals("B", s.suggestedNext().label);
        s.recordRun(b, run(20, 20, 20));
        assertEquals("A", s.suggestedNext().label);
        assertEquals(6, b.summary().count);
        assertEquals(20, b.summary().median, 1e-9);
        assertEquals(a, s.addProfile("a", "dup"));
        assertEquals(2, s.profiles().size());
    }
}
