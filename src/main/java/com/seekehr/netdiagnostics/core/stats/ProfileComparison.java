package com.seekehr.netdiagnostics.core.stats;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Compares two sets of successful Minecraft ping samples (profile A = baseline, profile B = candidate).
 *
 * <p>The difference in medians (B - A) is reported together with a 95% percentile-bootstrap
 * confidence interval (fixed seed, so results are reproducible). A route is only described as
 * faster when both profiles have at least {@link #MIN_SAMPLES} successful samples, the interval
 * excludes zero, and the absolute difference is at least {@link #MIN_MEANINGFUL_MS}.</p>
 */
public final class ProfileComparison {
    public static final int MIN_SAMPLES = 10;
    public static final int SOLID_SAMPLES = 30;
    public static final double MIN_MEANINGFUL_MS = 1.0;
    public static final int BOOTSTRAP_ITERATIONS = 2000;

    public enum Verdict {
        INSUFFICIENT_DATA, NO_MEANINGFUL_DIFFERENCE, INCONCLUSIVE, B_FASTER, A_FASTER
    }

    public final StatSummary a;
    public final StatSummary b;
    /** median(B) - median(A), ms. Negative means B is lower. */
    public final double medianDiff;
    public final double ciLow;
    public final double ciHigh;
    public final Verdict verdict;
    public final String variability;

    private ProfileComparison(StatSummary a, StatSummary b, double medianDiff, double ciLow, double ciHigh,
                              Verdict verdict, String variability) {
        this.a = a;
        this.b = b;
        this.medianDiff = medianDiff;
        this.ciLow = ciLow;
        this.ciHigh = ciHigh;
        this.verdict = verdict;
        this.variability = variability;
    }

    public static ProfileComparison compare(double[] samplesA, double[] samplesB) {
        StatSummary sa = RttStats.summarize(samplesA);
        StatSummary sb = RttStats.summarize(samplesB);
        if (sa.count < MIN_SAMPLES || sb.count < MIN_SAMPLES) {
            double diff = (sa.isEmpty() || sb.isEmpty()) ? Double.NaN : sb.median - sa.median;
            return new ProfileComparison(sa, sb, diff, Double.NaN, Double.NaN, Verdict.INSUFFICIENT_DATA,
                    variabilityText(sa, sb));
        }
        double diff = sb.median - sa.median;
        double[] ci = bootstrapMedianDiffCi(samplesA, samplesB, BOOTSTRAP_ITERATIONS, 0x5EEC3L);
        Verdict v;
        if (Math.abs(diff) < MIN_MEANINGFUL_MS) {
            v = Verdict.NO_MEANINGFUL_DIFFERENCE;
        } else if (ci[0] <= 0 && ci[1] >= 0) {
            v = Verdict.INCONCLUSIVE;
        } else {
            v = diff < 0 ? Verdict.B_FASTER : Verdict.A_FASTER;
        }
        return new ProfileComparison(sa, sb, diff, ci[0], ci[1], v, variabilityText(sa, sb));
    }

    /** Percentile bootstrap 95% CI for median(B) - median(A). */
    public static double[] bootstrapMedianDiffCi(double[] a, double[] b, int iterations, long seed) {
        Random rnd = new Random(seed);
        double[] diffs = new double[iterations];
        double[] ra = new double[a.length];
        double[] rb = new double[b.length];
        for (int it = 0; it < iterations; it++) {
            for (int i = 0; i < ra.length; i++) {
                ra[i] = a[rnd.nextInt(a.length)];
            }
            for (int i = 0; i < rb.length; i++) {
                rb[i] = b[rnd.nextInt(b.length)];
            }
            Arrays.sort(ra);
            Arrays.sort(rb);
            diffs[it] = RttStats.percentileSorted(rb, 50) - RttStats.percentileSorted(ra, 50);
        }
        Arrays.sort(diffs);
        return new double[]{RttStats.percentileSorted(diffs, 2.5), RttStats.percentileSorted(diffs, 97.5)};
    }

    static String variabilityText(StatSummary a, StatSummary b) {
        if (a.isEmpty() || b.isEmpty()) {
            return "variability not comparable";
        }
        double va = Math.max(a.iqr(), 0.5);
        double vb = Math.max(b.iqr(), 0.5);
        double ratio = vb / va;
        if (ratio < 0.75) {
            return "lower variability";
        }
        if (ratio > 1.33) {
            return "higher variability";
        }
        return "similar variability";
    }

    /** Plain-language recommendation that never overstates the evidence. */
    public String recommendation(String labelA, String labelB) {
        switch (verdict) {
            case INSUFFICIENT_DATA:
                return String.format(Locale.ROOT,
                        "Not enough successful samples to compare (%s: %d, %s: %d). Collect at least %d successful "
                                + "pings per profile, ideally %d+, before drawing conclusions.",
                        labelA, a.count, labelB, b.count, MIN_SAMPLES, SOLID_SAMPLES);
            case NO_MEANINGFUL_DIFFERENCE:
                return String.format(Locale.ROOT,
                        "%s and %s performed about the same: median ping differed by only %.1f ms (%s for %s).",
                        labelA, labelB, Math.abs(medianDiff), variability, labelB);
            case INCONCLUSIVE:
                return String.format(Locale.ROOT,
                        "No clear winner. %s's median ping was %.1f ms %s than %s, but the results overlap "
                                + "(95%% interval for the difference: %.1f to %.1f ms). Repeat the test (ABBA order) "
                                + "to reduce time-of-day effects.",
                        labelB, Math.abs(medianDiff), medianDiff < 0 ? "lower" : "higher", labelA, ciLow, ciHigh);
            case B_FASTER:
            case A_FASTER:
            default: {
                boolean bFaster = verdict == Verdict.B_FASTER;
                String winner = bFaster ? labelB : labelA;
                StatSummary w = bFaster ? b : a;
                String caution = (a.count < SOLID_SAMPLES || b.count < SOLID_SAMPLES)
                        ? " Sample size is still small; confirm with another ABBA round." : "";
                String var = bFaster ? variability : invert(variability);
                return String.format(Locale.ROOT,
                        "%s showed a %.1f ms lower median ping over %d successful pings, with %s.%s",
                        winner, Math.abs(medianDiff), w.count, var, caution);
            }
        }
    }

    private static String invert(String v) {
        if ("lower variability".equals(v)) {
            return "higher variability";
        }
        if ("higher variability".equals(v)) {
            return "lower variability";
        }
        return v;
    }
}
