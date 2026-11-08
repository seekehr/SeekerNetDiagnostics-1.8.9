package com.seekehr.netdiagnostics.core.stats;

import com.seekehr.netdiagnostics.core.compare.CompareSession;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProfileComparisonTest {
    private static double[] around(double center, double spread, int n, long seed) {
        Random r = new Random(seed);
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = center + (r.nextDouble() * 2 - 1) * spread;
        }
        return v;
    }

    @Test
    public void clearImprovementIsReported() {
        ProfileComparison c = ProfileComparison.compare(around(100, 1, 50, 1), around(96, 1, 50, 2));
        assertEquals(ProfileComparison.Verdict.B_FASTER, c.verdict);
        assertTrue(c.medianDiff < -3 && c.medianDiff > -5);
        assertTrue(c.ciHigh < 0);
        String rec = c.recommendation("Profile A", "Profile B");
        assertTrue(rec, rec.matches("Profile B showed a [34]\\.[0-9] ms lower median RTT over 50 successful TCP "
                + "connections, with similar variability\\."));
    }

    @Test
    public void baselineFasterIsReportedForA() {
        ProfileComparison c = ProfileComparison.compare(around(80, 1, 40, 3), around(95, 1, 40, 4));
        assertEquals(ProfileComparison.Verdict.A_FASTER, c.verdict);
        assertTrue(c.recommendation("Auto", "Singapore").startsWith("Auto showed a 15."));
    }

    @Test
    public void smallSamplesAreInsufficient() {
        ProfileComparison c = ProfileComparison.compare(around(100, 1, 5, 1), around(50, 1, 5, 2));
        assertEquals(ProfileComparison.Verdict.INSUFFICIENT_DATA, c.verdict);
        assertTrue(c.recommendation("A", "B").startsWith("Not enough successful samples"));
    }

    @Test
    public void overlappingNoisyResultsAreInconclusive() {
        ProfileComparison c = ProfileComparison.compare(around(100, 40, 15, 5), around(97, 40, 15, 6));
        assertTrue(c.verdict == ProfileComparison.Verdict.INCONCLUSIVE
                || c.verdict == ProfileComparison.Verdict.NO_MEANINGFUL_DIFFERENCE);
        assertTrue(c.recommendation("A", "B").contains("No clear winner")
                || c.recommendation("A", "B").contains("about the same"));
    }

    @Test
    public void identicalProfilesShowNoDifference() {
        double[] a = around(70, 2, 30, 9);
        ProfileComparison c = ProfileComparison.compare(a, a.clone());
        assertEquals(ProfileComparison.Verdict.NO_MEANINGFUL_DIFFERENCE, c.verdict);
        assertEquals(0, c.medianDiff, 1e-9);
    }

    @Test
    public void bootstrapIsDeterministic() {
        double[] a = around(100, 5, 30, 1);
        double[] b = around(90, 5, 30, 2);
        assertArrayEquals(ProfileComparison.bootstrapMedianDiffCi(a, b, 500, 42),
                ProfileComparison.bootstrapMedianDiffCi(a, b, 500, 42), 0);
    }

    @Test
    public void variabilityText() {
        StatSummary narrow = RttStats.summarize(around(50, 1, 30, 1));
        StatSummary wide = RttStats.summarize(around(50, 20, 30, 2));
        assertEquals("higher variability", ProfileComparison.variabilityText(narrow, wide));
        assertEquals("lower variability", ProfileComparison.variabilityText(wide, narrow));
    }

    @Test
    public void abbaOrder() {
        int[] two = new int[8];
        for (int i = 0; i < 8; i++) {
            two[i] = CompareSession.abbaIndex(i, 2);
        }
        assertArrayEquals(new int[]{0, 1, 1, 0, 0, 1, 1, 0}, two);
        int[] three = new int[6];
        for (int i = 0; i < 6; i++) {
            three[i] = CompareSession.abbaIndex(i, 3);
        }
        assertArrayEquals(new int[]{0, 1, 2, 2, 1, 0}, three);
        assertEquals(0, CompareSession.abbaIndex(5, 1));
    }
}
