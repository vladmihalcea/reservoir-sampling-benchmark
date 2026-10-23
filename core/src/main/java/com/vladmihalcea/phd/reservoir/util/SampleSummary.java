package com.vladmihalcea.phd.reservoir.util;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.function.ToDoubleFunction;

/**
 * The values of one measured quantity over repeated trials or seeds, kept in full so that, unlike
 * {@link Statistics}, it can report order statistics and resampled confidence intervals: the median, any empirical
 * quantile, the minimum and maximum, a Student-t interval for the mean (the normal approximation of
 * {@link Statistics} is too narrow for the five trials of the starvation experiments), and percentile-bootstrap
 * intervals for the mean, the median, or any other statistic.
 * <p>
 * The bootstrap draws from a seeded generator, so a summary of the same values always reports the same interval.
 * {@code NaN} values are ignored.
 *
 * @author Vlad Mihalcea
 */
public final class SampleSummary {

    /** Two-sided 97.5% quantiles of Student's t for 1 to 30 degrees of freedom. */
    private static final double[] T_975 = {
            12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
            2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
            2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042};

    public static final int DEFAULT_RESAMPLES = 2_000;
    private static final long BOOTSTRAP_SEED = 0x5EEDL;

    private double[] values = new double[8];
    private int n;
    private double[] sorted;

    public void add(double value) {
        if (Double.isNaN(value)) {
            return;
        }
        if (n == values.length) {
            values = Arrays.copyOf(values, n * 2);
        }
        values[n++] = value;
        sorted = null;
    }

    public int count() {
        return n;
    }

    public double[] values() {
        return Arrays.copyOf(values, n);
    }

    public double mean() {
        return mean(values, n);
    }

    public double stdDev() {
        if (n < 2) {
            return 0.0;
        }
        double mean = mean();
        double squares = 0;
        for (int i = 0; i < n; i++) {
            squares += (values[i] - mean) * (values[i] - mean);
        }
        return Math.sqrt(squares / (n - 1));
    }

    /** Half-width of the 95% Student-t interval for the mean; 0 for fewer than 2 values. */
    public double tCi95HalfWidth() {
        if (n < 2) {
            return 0.0;
        }
        int df = n - 1;
        double t = df <= T_975.length ? T_975[df - 1] : 1.96;
        return t * stdDev() / Math.sqrt(n);
    }

    public double median() {
        return quantile(0.5);
    }

    public double min() {
        return n == 0 ? Double.NaN : sorted()[0];
    }

    public double max() {
        return n == 0 ? Double.NaN : sorted()[n - 1];
    }

    /** The empirical {@code q}-quantile, linearly interpolated (R-7), as {@link Quantiles} defines it. */
    public double quantile(double q) {
        return n == 0 ? Double.NaN : quantileOfSorted(sorted(), n, q);
    }

    /** The 95% percentile-bootstrap interval of the mean: {@code {lower, upper}}. */
    public double[] bootstrapMeanCi95() {
        return bootstrapCi(sample -> mean(sample, sample.length), 0.95, DEFAULT_RESAMPLES);
    }

    /** The 95% percentile-bootstrap interval of the median: {@code {lower, upper}}. */
    public double[] bootstrapMedianCi95() {
        return bootstrapCi(sample -> {
            double[] copy = sample.clone();
            Arrays.sort(copy);
            return quantileOfSorted(copy, copy.length, 0.5);
        }, 0.95, DEFAULT_RESAMPLES);
    }

    /**
     * The percentile-bootstrap interval of {@code statistic} at the given confidence: the values are resampled with
     * replacement {@code resamples} times, and the interval spans the central {@code level} of the resampled
     * statistics. With fewer than 2 values, both bounds are the statistic of the values themselves.
     */
    public double[] bootstrapCi(ToDoubleFunction<double[]> statistic, double level, int resamples) {
        double[] own = values();
        if (n < 2) {
            double s = n == 0 ? Double.NaN : statistic.applyAsDouble(own);
            return new double[]{s, s};
        }
        SplittableRandom random = new SplittableRandom(BOOTSTRAP_SEED);
        double[] stats = new double[resamples];
        double[] sample = new double[n];
        for (int r = 0; r < resamples; r++) {
            for (int i = 0; i < n; i++) {
                sample[i] = own[random.nextInt(n)];
            }
            stats[r] = statistic.applyAsDouble(sample);
        }
        Arrays.sort(stats);
        double tail = (1 - level) / 2;
        return new double[]{quantileOfSorted(stats, resamples, tail), quantileOfSorted(stats, resamples, 1 - tail)};
    }

    private double[] sorted() {
        if (sorted == null) {
            sorted = Arrays.copyOf(values, n);
            Arrays.sort(sorted);
        }
        return sorted;
    }

    private static double mean(double[] data, int length) {
        if (length == 0) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = 0; i < length; i++) {
            sum += data[i];
        }
        return sum / length;
    }

    private static double quantileOfSorted(double[] data, int length, double q) {
        if (q <= 0) {
            return data[0];
        }
        if (q >= 1) {
            return data[length - 1];
        }
        double pos = q * (length - 1);
        int lower = (int) Math.floor(pos);
        int upper = (int) Math.ceil(pos);
        double weight = pos - lower;
        return data[lower] * (1 - weight) + data[upper] * weight;
    }
}
