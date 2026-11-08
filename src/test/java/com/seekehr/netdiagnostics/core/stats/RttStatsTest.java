package com.seekehr.netdiagnostics.core.stats;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RttStatsTest {
    private static final double EPS = 1e-9;

    @Test
    public void summarizeKnownValues() {
        StatSummary s = RttStats.summarize(new double[]{10, 20, 30, 40, 50});
        assertEquals(5, s.count);
        assertEquals(30, s.mean, EPS);
        assertEquals(30, s.median, EPS);
        assertEquals(10, s.min, EPS);
        assertEquals(50, s.max, EPS);
        assertEquals(Math.sqrt(250), s.stdDev, EPS); // sample SD, n-1
        assertEquals(48, s.p95, EPS); // h = 4*0.95 = 3.8 -> 40 + 0.8*10
        assertEquals(49.6, s.p99, EPS);
        assertEquals(10, s.jitter, EPS);
        assertEquals(20, s.p25, EPS);
        assertEquals(40, s.p75, EPS);
        assertEquals(20, s.iqr(), EPS);
    }

    @Test
    public void orderMattersForJitterButNotForPercentiles() {
        StatSummary a = RttStats.summarize(new double[]{10, 50, 10, 50});
        StatSummary b = RttStats.summarize(new double[]{10, 10, 50, 50});
        assertEquals(a.median, b.median, EPS);
        assertEquals(a.p95, b.p95, EPS);
        assertEquals(40, a.jitter, EPS);
        assertEquals(40.0 / 3, b.jitter, EPS);
    }

    @Test
    public void emptyAndNullGiveEmptySummary() {
        assertSame(StatSummary.EMPTY, RttStats.summarize(new double[0]));
        assertSame(StatSummary.EMPTY, RttStats.summarize(null));
        assertTrue(StatSummary.EMPTY.isEmpty());
        assertTrue(Double.isNaN(StatSummary.EMPTY.median));
    }

    @Test
    public void singleSample() {
        StatSummary s = RttStats.summarize(new double[]{42});
        assertEquals(1, s.count);
        assertEquals(42, s.median, EPS);
        assertEquals(42, s.p99, EPS);
        assertEquals(0, s.stdDev, EPS);
        assertEquals(0, s.jitter, EPS);
    }

    @Test
    public void rejectsInvalidSamples() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            try {
                RttStats.summarize(new double[]{1, bad});
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void percentileInterpolation() {
        double[] sorted = {1, 2, 3, 4};
        assertEquals(1, RttStats.percentileSorted(sorted, 0), EPS);
        assertEquals(4, RttStats.percentileSorted(sorted, 100), EPS);
        assertEquals(2.5, RttStats.percentileSorted(sorted, 50), EPS);
        assertEquals(1.75, RttStats.percentileSorted(sorted, 25), EPS);
        assertEquals(3.85, RttStats.percentileSorted(sorted, 95), EPS);
        assertTrue(Double.isNaN(RttStats.percentileSorted(new double[0], 50)));
        assertEquals(7, RttStats.percentile(new double[]{9, 7, 5}, 50), EPS);
    }

    @Test(expected = IllegalArgumentException.class)
    public void percentileOutOfRange() {
        RttStats.percentileSorted(new double[]{1, 2}, 101);
    }

    @Test
    public void percentileOfLargeUniformSample() {
        double[] v = new double[1000];
        for (int i = 0; i < v.length; i++) {
            v[i] = i + 1; // 1..1000
        }
        assertEquals(950.05, RttStats.percentile(v, 95), 1e-6);
        assertEquals(990.01, RttStats.percentile(v, 99), 1e-6);
        assertEquals(500.5, RttStats.median(v), 1e-6);
    }

    @Test
    public void jitterDefinitions() {
        double[] v = {100, 104, 100, 108};
        assertEquals((4 + 4 + 8) / 3.0, RttStats.jitter(v, v.length), EPS);
        assertEquals(0, RttStats.jitter(v, 1), EPS);
        // RFC 3550 smoothing: J1 = 4/16, J2 = J1 + (4-J1)/16, J3 = J2 + (8-J2)/16
        double j = 0;
        j += (4 - j) / 16;
        j += (4 - j) / 16;
        j += (8 - j) / 16;
        assertEquals(j, RttStats.rfc3550Jitter(v, v.length), EPS);
        assertEquals(0, RttStats.jitter(new double[]{5, 5, 5}, 3), EPS);
    }

    @Test
    public void medianAbsoluteDeviation() {
        assertEquals(1, RttStats.mad(new double[]{1, 1, 2, 2, 4, 6, 9}), EPS);
    }
}
