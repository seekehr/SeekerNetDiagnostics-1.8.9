package com.seekehr.netdiagnostics.core.stats;

import java.util.Arrays;

/**
 * Statistical helpers for latency samples.
 *
 * <h3>Percentiles</h3>
 * Percentiles use linear interpolation between closest ranks on the sorted sample
 * (the "R-7" / Excel {@code PERCENTILE.INC} definition): for {@code n} sorted values and percentile
 * {@code p} in [0, 100], {@code h = (n - 1) * p / 100}; the result is
 * {@code x[floor(h)] + (h - floor(h)) * (x[floor(h) + 1] - x[floor(h)])}.
 *
 * <h3>Jitter</h3>
 * Jitter is the mean absolute difference between consecutive samples in the order they were
 * measured: {@code J = (1 / (n - 1)) * sum(|x[i] - x[i-1]|)} for i = 1..n-1. This is the unsmoothed
 * form of the inter-arrival jitter in RFC 3550 section 6.4.1 applied to round-trip samples. It is 0
 * for fewer than two samples. {@link #rfc3550Jitter(double[], int)} provides the smoothed variant
 * ({@code J += (|D| - J) / 16}).
 */
public final class RttStats {
    private RttStats() {
    }

    public static StatSummary summarize(double[] samplesInOrder) {
        return summarize(samplesInOrder, samplesInOrder == null ? 0 : samplesInOrder.length);
    }

    /**
     * Summarise the first {@code n} values of {@code samplesInOrder} (measurement order is preserved
     * for the jitter calculation). Non-finite values are rejected with an exception because they
     * indicate a bug upstream (failed attempts must never be passed in as samples).
     */
    public static StatSummary summarize(double[] samplesInOrder, int n) {
        if (samplesInOrder == null || n <= 0) {
            return StatSummary.EMPTY;
        }
        if (n > samplesInOrder.length) {
            throw new IllegalArgumentException("n exceeds array length");
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double v = samplesInOrder[i];
            if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) {
                throw new IllegalArgumentException("invalid sample at index " + i + ": " + v);
            }
            sum += v;
        }
        double mean = sum / n;
        double sq = 0;
        for (int i = 0; i < n; i++) {
            double d = samplesInOrder[i] - mean;
            sq += d * d;
        }
        double sd = n > 1 ? Math.sqrt(sq / (n - 1)) : 0.0;
        double jitter = jitter(samplesInOrder, n);
        double[] sorted = Arrays.copyOf(samplesInOrder, n);
        Arrays.sort(sorted);
        return new StatSummary(n, mean, percentileSorted(sorted, 50), sorted[0], sorted[n - 1],
                percentileSorted(sorted, 95), percentileSorted(sorted, 99), sd, jitter,
                percentileSorted(sorted, 25), percentileSorted(sorted, 75));
    }

    /** Percentile of an already-sorted array using linear interpolation (R-7). */
    public static double percentileSorted(double[] sorted, double p) {
        return percentileSorted(sorted, sorted.length, p);
    }

    public static double percentileSorted(double[] sorted, int n, double p) {
        if (n <= 0) {
            return Double.NaN;
        }
        if (p < 0 || p > 100) {
            throw new IllegalArgumentException("percentile out of range: " + p);
        }
        if (n == 1) {
            return sorted[0];
        }
        double h = (n - 1) * (p / 100.0);
        int lo = (int) Math.floor(h);
        int hi = Math.min(lo + 1, n - 1);
        double frac = h - lo;
        return sorted[lo] + frac * (sorted[hi] - sorted[lo]);
    }

    /** Percentile of unsorted data (copies and sorts). */
    public static double percentile(double[] values, double p) {
        if (values == null || values.length == 0) {
            return Double.NaN;
        }
        double[] copy = values.clone();
        Arrays.sort(copy);
        return percentileSorted(copy, p);
    }

    public static double median(double[] values) {
        return percentile(values, 50);
    }

    /** Mean absolute successive difference; see class documentation. */
    public static double jitter(double[] samplesInOrder, int n) {
        if (n < 2) {
            return 0.0;
        }
        double acc = 0;
        for (int i = 1; i < n; i++) {
            acc += Math.abs(samplesInOrder[i] - samplesInOrder[i - 1]);
        }
        return acc / (n - 1);
    }

    /** RFC 3550 style exponentially smoothed jitter (gain 1/16) over consecutive differences. */
    public static double rfc3550Jitter(double[] samplesInOrder, int n) {
        double j = 0;
        for (int i = 1; i < n; i++) {
            double d = Math.abs(samplesInOrder[i] - samplesInOrder[i - 1]);
            j += (d - j) / 16.0;
        }
        return j;
    }

    /** Median absolute deviation (unscaled). */
    public static double mad(double[] values) {
        if (values == null || values.length == 0) {
            return Double.NaN;
        }
        double med = median(values);
        double[] dev = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            dev[i] = Math.abs(values[i] - med);
        }
        return median(dev);
    }
}
