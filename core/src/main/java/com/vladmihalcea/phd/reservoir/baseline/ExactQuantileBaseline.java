package com.vladmihalcea.phd.reservoir.baseline;

import com.vladmihalcea.phd.reservoir.util.Quantiles;

import java.util.Arrays;

/**
 * Ground-truth quantile baseline: retains the entire (optionally time-windowed) stream and computes
 * exact quantiles by sorting. Accuracy is reported against this baseline. For decay-aware comparisons it
 * can be restricted to the most recent suffix so that recency-preserving strategies are judged against
 * the recent true distribution rather than the all-time one.
 *
 * @author Vlad Mihalcea
 */
public final class ExactQuantileBaseline {

    private long[] values;
    private int length;

    public ExactQuantileBaseline() {
        this.values = new long[1024];
    }

    public void add(long value) {
        if (length == values.length) {
            values = Arrays.copyOf(values, values.length * 2);
        }
        values[length++] = value;
    }

    public double valueAtQuantile(double q) {
        return Quantiles.valueAtQuantile(values, length, q);
    }

    /**
     * Exact quantile over the most recent {@code suffix} observations only.
     */
    public double valueAtQuantileOverSuffix(double q, int suffix) {
        int from = Math.max(0, length - suffix);
        int n = length - from;
        long[] window = Arrays.copyOfRange(values, from, length);
        Arrays.sort(window);
        return Quantiles.valueAtQuantileSorted(window, n, q);
    }

    /**
     * Exact quantile computed directly over a supplied array (does not touch retained state).
     */
    public static double of(long[] data, int length, double q) {
        return Quantiles.valueAtQuantile(data, length, q);
    }

    /** Exact quantile over an array that is already sorted ascending, without copying it. */
    public static double ofSorted(long[] sorted, int length, double q) {
        return Quantiles.valueAtQuantileSorted(sorted, length, q);
    }

    /**
     * The empirical CDF of an ascending array at {@code x}: the share of the values at or below it. The rank error of
     * an estimate, {@code |F(estimate) - q|}, is the error that the sampling guarantees bound; the relative value
     * error is what an operator reads.
     */
    public static double cdfSorted(long[] sorted, int length, double x) {
        if (length == 0 || Double.isNaN(x)) {
            return Double.NaN;
        }
        int lo = 0;
        int hi = length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] <= x) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo / (double) length;
    }

    public int size() {
        return length;
    }

    public void reset() {
        length = 0;
    }

    // -------------------------------------------------------------------------
    // Error metrics
    // -------------------------------------------------------------------------

    /**
     * Relative value error {@code |estimate - truth| / truth}, guarded against a zero truth.
     */
    public static double relativeError(double estimate, double truth) {
        if (truth == 0.0) {
            return estimate == 0.0 ? 0.0 : Double.POSITIVE_INFINITY;
        }
        return Math.abs(estimate - truth) / Math.abs(truth);
    }
}
