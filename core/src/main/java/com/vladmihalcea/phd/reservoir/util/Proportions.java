package com.vladmihalcea.phd.reservoir.util;

/**
 * Helpers for the detection experiments, which ask how often a recorder shows a rare event (a timeout or a stall) at
 * all: the Wilson score interval of an observed proportion, and the probability that a uniform reservoir retains at
 * least one of the rare values, the reference curve every fixed-size reservoir is compared with.
 *
 * @author Vlad Mihalcea
 */
public final class Proportions {

    private Proportions() {
    }

    /** The 95% Wilson score interval of {@code successes} out of {@code trials}: {@code {lower, upper}}. */
    public static double[] wilson95(long successes, long trials) {
        if (trials == 0) {
            return new double[]{Double.NaN, Double.NaN};
        }
        double z = 1.959964;
        double p = successes / (double) trials;
        double denominator = 1 + z * z / trials;
        double center = (p + z * z / (2.0 * trials)) / denominator;
        double half = z * Math.sqrt(p * (1 - p) / trials + z * z / (4.0 * trials * trials)) / denominator;
        return new double[]{Math.max(0, center - half), Math.min(1, center + half)};
    }

    /**
     * The probability that a uniform sample of {@code k} of {@code n} values, drawn without replacement as reservoir
     * sampling (Vitter's Algorithm R) leaves it, contains at least one of {@code rare} given values:
     * {@code 1 - C(n - rare, k) / C(n, k)}, about {@code 1 - exp(-k * rare / n)} when {@code k} is small next to
     * {@code n}. With {@code k >= n} every value is retained.
     */
    public static double uniformReservoirHit(long n, long rare, int k) {
        if (rare <= 0) {
            return 0;
        }
        if (k >= n || rare > n - k) {
            return 1;
        }
        double logNone = 0;
        for (int i = 0; i < k; i++) {
            logNone += Math.log1p(-rare / (double) (n - i));
        }
        return -Math.expm1(logNone);
    }
}
