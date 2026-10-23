package com.vladmihalcea.phd.reservoir.baseline;

import com.vladmihalcea.phd.reservoir.util.Quantiles;

import java.util.Arrays;

/**
 * The exact distribution a strategy claims to summarize at the end of a timestamped stream, which is not always the
 * whole stream: a forward-decay reservoir samples each value with a weight that grows as {@code exp(alpha t)}, a
 * sliding window keeps the values of its last {@code W} seconds, and the VictoriaMetrics summary reads the reservoir
 * of the half-window before its last swap. Scoring those strategies only against the all-time quantiles, as the
 * survey's E6 did, charges them for doing their job: on the regime-shift workload, the decaying and windowed
 * recorders reported the new regime and showed a p50 error of 2.16 against the all-time median.
 * <p>
 * A target holds its values sorted, with their cumulative weights for the decayed target, and answers two questions:
 * the value at a quantile (the R-7 interpolation of {@link Quantiles} for unweighted targets, so the all-time numbers
 * are those of {@link ExactQuantileBaseline}; the weighted inverse CDF for the decayed one), and the CDF at a value,
 * from which the rank error follows ({@link #rankError}).
 *
 * @author Vlad Mihalcea
 */
public final class TargetTruth {

    /** Which part of the stream, with which weights, a strategy summarizes. */
    public sealed interface Target permits AllTime, Decayed, Window, LastHalfWindow {

        /** A short name for the CSVs. */
        String label();
    }

    /** Every value, with equal weight. */
    public record AllTime() implements Target {
        @Override
        public String label() {
            return "all-time";
        }
    }

    /** Every value, weighted by {@code exp(alpha * t)} with {@code t} in seconds (forward decay). */
    public record Decayed(double alphaPerSecond) implements Target {
        @Override
        public String label() {
            return "decayed-" + alphaPerSecond;
        }
    }

    /** The values of the last {@code windowNanos} before the end of the stream (a sliding time window). */
    public record Window(long windowNanos) implements Target {
        @Override
        public String label() {
            return "window-" + windowNanos / 1_000_000_000L + "s";
        }
    }

    /**
     * The values of the half-window that ended at the last swap of a two-reservoir summary whose swaps fall at the
     * multiples of {@code halfWindowNanos} of the clock (VictoriaMetrics, created at clock zero); before the first
     * swap, every value.
     */
    public record LastHalfWindow(long halfWindowNanos) implements Target {
        @Override
        public String label() {
            return "last-half-window-" + halfWindowNanos / 1_000_000_000L + "s";
        }
    }

    private final long[] sorted;
    private final double[] cumulativeWeights;
    private final int length;

    private TargetTruth(long[] sorted, double[] cumulativeWeights) {
        this.sorted = sorted;
        this.cumulativeWeights = cumulativeWeights;
        this.length = sorted.length;
    }

    /**
     * The target distribution of {@code values} observed at {@code timestampsNanos} (ascending), read at the time of
     * the last value.
     */
    public static TargetTruth of(Target target, long[] values, long[] timestampsNanos) {
        int n = values.length;
        long end = n == 0 ? 0 : timestampsNanos[n - 1];
        return switch (target) {
            case AllTime a -> unweighted(values, 0, n);
            case Window w -> {
                int from = firstAtOrAfter(timestampsNanos, end - w.windowNanos() + 1);
                yield unweighted(values, from, n);
            }
            case LastHalfWindow h -> {
                long lastSwap = (end / h.halfWindowNanos()) * h.halfWindowNanos();
                if (lastSwap == 0) {
                    yield unweighted(values, 0, n);
                }
                int from = firstAtOrAfter(timestampsNanos, lastSwap - h.halfWindowNanos());
                int to = firstAtOrAfter(timestampsNanos, lastSwap);
                yield unweighted(values, from, to);
            }
            case Decayed d -> decayed(values, timestampsNanos, end, d.alphaPerSecond());
        };
    }

    public int size() {
        return length;
    }

    /** The exact value at quantile {@code q} of the target. */
    public double valueAtQuantile(double q) {
        if (length == 0) {
            return Double.NaN;
        }
        if (cumulativeWeights == null) {
            return Quantiles.valueAtQuantileSorted(sorted, length, q);
        }
        double total = cumulativeWeights[length - 1];
        double goal = q * total;
        int lo = 0;
        int hi = length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulativeWeights[mid] >= goal) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return sorted[lo];
    }

    /** The share of the target's weight at or below {@code x}: {@code F(x)}. */
    public double cdf(double x) {
        return share(x, true);
    }

    /** The share of the target's weight strictly below {@code x}: {@code F(x-)}. */
    public double cdfBelow(double x) {
        return share(x, false);
    }

    /**
     * The rank error of an estimate of the {@code q}-quantile: the distance from {@code q} to the ranks the estimate
     * occupies, {@code [F(estimate-), F(estimate)]}, so an estimate that is exactly the true quantile scores 0 even when
     * its value carries a visible share of the weight (one recent value of a strongly decayed stream does). Between two
     * values, where the CDF has no jump, it is {@code |F(estimate) - q|}.
     */
    public double rankError(double estimate, double q) {
        if (Double.isNaN(estimate) || length == 0) {
            return Double.NaN;
        }
        double below = cdfBelow(estimate);
        double atOrBelow = cdf(estimate);
        return Math.max(0, Math.max(below - q, q - atOrBelow));
    }

    private double share(double x, boolean inclusive) {
        if (length == 0 || Double.isNaN(x)) {
            return Double.NaN;
        }
        int lo = 0;
        int hi = length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (inclusive ? sorted[mid] <= x : sorted[mid] < x) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if (cumulativeWeights == null) {
            return lo / (double) length;
        }
        return lo == 0 ? 0 : cumulativeWeights[lo - 1] / cumulativeWeights[length - 1];
    }

    private static TargetTruth unweighted(long[] values, int from, int to) {
        long[] copy = Arrays.copyOfRange(values, from, Math.max(from, to));
        Arrays.sort(copy);
        return new TargetTruth(copy, null);
    }

    private static TargetTruth decayed(long[] values, long[] timestamps, long end, double alpha) {
        int n = values.length;
        // Sort the values while carrying their weights: pack value and index into one long when they fit.
        long max = 0;
        for (long v : values) {
            max = Math.max(max, v);
        }
        int indexBits = 64 - Long.numberOfLeadingZeros(Math.max(1, n - 1));
        Integer[] boxed = null;
        long[] packed = null;
        if (max >= 0 && indexBits + (64 - Long.numberOfLeadingZeros(Math.max(1, max))) < 63) {
            packed = new long[n];
            for (int i = 0; i < n; i++) {
                packed[i] = (values[i] << indexBits) | i;
            }
            Arrays.sort(packed);
        } else {
            boxed = new Integer[n];
            for (int i = 0; i < n; i++) {
                boxed[i] = i;
            }
            Arrays.sort(boxed, (a, b) -> Long.compare(values[a], values[b]));
        }
        long mask = (1L << indexBits) - 1;
        long[] sorted = new long[n];
        double[] cumulative = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            int index = packed != null ? (int) (packed[i] & mask) : boxed[i];
            sorted[i] = values[index];
            // Relative to the end of the stream, so the weights stay in range: exp(alpha * (t - end)) <= 1.
            sum += Math.exp(alpha * (timestamps[index] - end) / 1e9);
            cumulative[i] = sum;
        }
        return new TargetTruth(sorted, cumulative);
    }

    /** The first index whose timestamp is at or after {@code time}, or the length if none is. */
    private static int firstAtOrAfter(long[] timestamps, long time) {
        int lo = 0;
        int hi = timestamps.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (timestamps[mid] < time) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
