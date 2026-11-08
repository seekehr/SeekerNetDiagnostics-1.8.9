package com.seekehr.netdiagnostics.core.stats;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SampleRingTest {
    @Test
    public void fillsAndWrapsKeepingNewest() {
        SampleRing r = new SampleRing(4);
        assertTrue(r.isEmpty());
        for (int i = 1; i <= 6; i++) {
            r.add(i * 1000L, i);
        }
        assertEquals(4, r.size());
        assertEquals(6, r.totalWritten());
        assertArrayEquals(new double[]{3, 4, 5, 6}, r.toArray(), 0);
        assertEquals(3000L, r.time(0));
        assertEquals(6, r.latestValue(), 0);
        assertEquals(6000L, r.latestTime());
    }

    @Test
    public void firstIndexSinceUsesTimestamps() {
        SampleRing r = new SampleRing(8);
        for (int i = 0; i < 10; i++) {
            r.add(i * 100L, i);
        }
        // retained: t=200..900
        assertEquals(0, r.firstIndexSince(0));
        assertEquals(0, r.firstIndexSince(200));
        assertEquals(1, r.firstIndexSince(201));
        assertEquals(5, r.firstIndexSince(700));
        assertEquals(8, r.firstIndexSince(10_000));
    }

    @Test
    public void copyValuesSinceIsBoundedByOutput() {
        SampleRing r = new SampleRing(10);
        for (int i = 0; i < 10; i++) {
            r.add(i, i * 10);
        }
        double[] out = new double[3];
        int n = r.copyValuesSince(0, out);
        assertEquals(3, n);
        assertArrayEquals(new double[]{70, 80, 90}, out, 0);
        double[] big = new double[20];
        assertEquals(5, r.copyValuesSince(5, big));
        assertEquals(50, big[0], 0);
    }

    @Test
    public void flagsFollowSamples() {
        SampleRing r = new SampleRing(2);
        r.add(1, 1, SampleRing.FLAG_SPIKE);
        r.add(2, 2);
        r.add(3, 3, SampleRing.FLAG_SPIKE);
        assertEquals(SampleRing.FLAG_NONE, r.flag(0));
        assertEquals(SampleRing.FLAG_SPIKE, r.flag(1));
        r.setFlag(0, SampleRing.FLAG_SPIKE);
        assertEquals(SampleRing.FLAG_SPIKE, r.flag(0));
    }

    @Test
    public void clearResets() {
        SampleRing r = new SampleRing(3);
        r.add(1, 1);
        r.clear();
        assertEquals(0, r.size());
        assertTrue(Double.isNaN(r.latestValue()));
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void outOfRangeIndex() {
        SampleRing r = new SampleRing(3);
        r.add(1, 1);
        r.value(1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroCapacityRejected() {
        new SampleRing(0);
    }
}
