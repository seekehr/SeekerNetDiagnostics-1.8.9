package com.seekehr.netdiagnostics.core.stats;

/**
 * Immutable summary of a set of latency samples (all values in milliseconds).
 *
 * <p>When {@link #count} is zero every other field is {@link Double#NaN}; callers must check
 * {@link #isEmpty()} before presenting numbers so that a failed test is never shown as a latency.</p>
 */
public final class StatSummary {
    public static final StatSummary EMPTY = new StatSummary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

    public final int count;
    public final double mean;
    public final double median;
    public final double min;
    public final double max;
    public final double p95;
    public final double p99;
    /** Sample standard deviation (n - 1 denominator); 0 for a single sample. */
    public final double stdDev;
    /** Mean absolute difference between consecutive samples (see {@link RttStats#jitter}). */
    public final double jitter;
    public final double p25;
    public final double p75;

    public StatSummary(int count, double mean, double median, double min, double max, double p95, double p99,
                       double stdDev, double jitter, double p25, double p75) {
        this.count = count;
        this.mean = mean;
        this.median = median;
        this.min = min;
        this.max = max;
        this.p95 = p95;
        this.p99 = p99;
        this.stdDev = stdDev;
        this.jitter = jitter;
        this.p25 = p25;
        this.p75 = p75;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    /** Interquartile range (p75 - p25). */
    public double iqr() {
        return p75 - p25;
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "StatSummary{no successful samples}";
        }
        return String.format(java.util.Locale.ROOT,
                "StatSummary{n=%d, mean=%.2f, median=%.2f, min=%.2f, max=%.2f, p95=%.2f, p99=%.2f, sd=%.2f, jitter=%.2f}",
                count, mean, median, min, max, p95, p99, stdDev, jitter);
    }
}
