package com.vladmihalcea.phd.reservoir.util;

import java.util.Arrays;

/**
 * Quantile helpers shared by the sorting-based reservoir strategies and the exact quantile baseline.
 * Uses the "R-7" linear-interpolation definition (the default in R, NumPy and Excel), which is
 * also what Dropwizard's {@code Snapshot} applies to a materialised reservoir.
 *
 * @author Vlad Mihalcea
 */
public final class Quantiles {

    private Quantiles() {
    }

    /**
     * Value at quantile over an already-sorted, ascending array of the given length.
     */
    public static double valueAtQuantileSorted(long[] sortedValues, int length, double quantile) {
        if (length == 0) {
            return Double.NaN;
        }
        if (quantile <= 0.0) {
            return sortedValues[0];
        }
        if (quantile >= 1.0) {
            return sortedValues[length - 1];
        }
        double pos = quantile * (length - 1);
        int lower = (int) Math.floor(pos);
        int upper = (int) Math.ceil(pos);
        if (lower == upper) {
            return sortedValues[lower];
        }
        double weight = pos - lower;
        return sortedValues[lower] * (1.0 - weight) + sortedValues[upper] * weight;
    }

    /**
     * Copies the first {@code length} values, sorts the copy and returns the value at the quantile.
     */
    public static double valueAtQuantile(long[] values, int length, double quantile) {
        long[] copy = Arrays.copyOf(values, length);
        Arrays.sort(copy);
        return valueAtQuantileSorted(copy, length, quantile);
    }
}
