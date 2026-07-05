package com.vladmihalcea.phd.reservoir.util;

/**
 * Small aggregation helper for reporting a mean with a 95% confidence interval over repeated seeded
 * runs, as required for publication-grade error bars.
 *
 * @author Vlad Mihalcea
 */
public final class Statistics {

    private double sum;
    private double sumSquares;
    private int n;

    public void add(double value) {
        sum += value;
        sumSquares += value * value;
        n++;
    }

    public int count() {
        return n;
    }

    public double mean() {
        return n == 0 ? Double.NaN : sum / n;
    }

    public double stdDev() {
        if (n < 2) {
            return 0.0;
        }
        double mean = mean();
        double variance = (sumSquares - n * mean * mean) / (n - 1);
        return Math.sqrt(Math.max(variance, 0.0));
    }

    /**
     * Half-width of the 95% confidence interval, using the normal approximation
     * {@code 1.96 * s / sqrt(n)} (adequate for the 30+ repetitions the experiments use).
     */
    public double ci95HalfWidth() {
        if (n < 2) {
            return 0.0;
        }
        return 1.96 * stdDev() / Math.sqrt(n);
    }
}
