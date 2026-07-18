package com.vladmihalcea.phd.reservoir.impl;

import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;
import com.vladmihalcea.phd.reservoir.util.Quantiles;

import java.util.SplittableRandom;

/**
 * Algorithm L (Li, 1994): the standard skip-based optimization of the uniform reservoir. Instead of
 * drawing a random number for every element, it draws the number of elements to skip from a
 * geometric distribution, giving {@code O(k(1 + log(n/k)))} random draws instead of {@code O(n)}.
 * <p>
 * This is the "skip optimization" whose absence in Dropwizard the survey highlights (the point
 * attributed to Meligrana). It is exercised here in a push (streaming) model: {@link #update(long)}
 * decrements a running skip counter and only touches the reservoir when the counter reaches zero.
 *
 * @author Vlad Mihalcea
 */
public class SkipReservoirL implements SamplingStrategy {

    private final int k;
    private final long[] reservoir;
    private final SplittableRandom random;
    private long count;
    private long skipRemaining;
    private double w;

    public SkipReservoirL(int k, long seed) {
        this.k = k;
        this.reservoir = new long[k];
        this.random = new SplittableRandom(seed);
        reset();
    }

    @Override
    public void update(long value) {
        count++;
        if (count <= k) {
            reservoir[(int) (count - 1)] = value;
            if (count == k) {
                primeFirstSkip();
            }
            return;
        }
        if (skipRemaining > 0) {
            skipRemaining--;
            return;
        }
        // The skip has elapsed: this element replaces a uniformly chosen slot.
        reservoir[random.nextInt(k)] = value;
        drawNextSkip();
    }

    private void primeFirstSkip() {
        w = Math.exp(Math.log(nextPositiveDouble()) / k);
        drawNextSkip();
    }

    private void drawNextSkip() {
        skipRemaining = (long) Math.floor(Math.log(nextPositiveDouble()) / Math.log(1.0 - w));
        w *= Math.exp(Math.log(nextPositiveDouble()) / k);
    }

    private double nextPositiveDouble() {
        double u = random.nextDouble();
        return u <= 0.0 ? Double.MIN_VALUE : u;
    }

    @Override
    public double valueAtQuantile(double q) {
        int filled = (int) Math.min(count, k);
        return Quantiles.valueAtQuantile(reservoir, filled, q);
    }

    @Override
    public void reset() {
        count = 0;
        skipRemaining = 0;
        w = 1.0;
    }

    @Override
    public String id() {
        return "Vitter-L-skip(k=" + k + ")";
    }

    @Override
    public Taxonomy taxonomy() {
        // Algorithm L is uniform reservoir sampling: the equal-weight (unweighted) case of
        // without-replacement sampling. Hübschle-Schneider and Sanders class it WRS-N, not WRS-R.
        return Taxonomy.WRS_N;
    }
}
