package com.vladmihalcea.phd.reservoir.impl;

import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;
import com.vladmihalcea.phd.reservoir.util.Quantiles;

import java.util.SplittableRandom;

/**
 * Vitter's Algorithm R (1985): the canonical uniform reservoir of fixed size {@code k}.
 * Every element seen so far is retained with equal probability {@code k/n}. This is the algorithm
 * that Dropwizard's {@code UniformReservoir} and valyala/histogram (VictoriaMetrics) implement.
 * <p>
 * Cost is {@code O(1)} per element but draws one random number for <em>every</em> element after the
 * reservoir fills — the baseline that the skip-based {@link SkipReservoirL} improves upon.
 *
 * @author Vlad Mihalcea
 */
public class VitterR implements SamplingStrategy {

    private final int k;
    private final long[] reservoir;
    private final SplittableRandom random;
    private long count;

    public VitterR(int k, long seed) {
        this.k = k;
        this.reservoir = new long[k];
        this.random = new SplittableRandom(seed);
    }

    @Override
    public void update(long value) {
        count++;
        if (count <= k) {
            reservoir[(int) (count - 1)] = value;
        } else {
            long j = random.nextLong(count);
            if (j < k) {
                reservoir[(int) j] = value;
            }
        }
    }

    @Override
    public double valueAtQuantile(double q) {
        int filled = (int) Math.min(count, k);
        return Quantiles.valueAtQuantile(reservoir, filled, q);
    }

    @Override
    public void reset() {
        count = 0;
    }

    @Override
    public String id() {
        return "Vitter-R(k=" + k + ")";
    }

    @Override
    public Taxonomy taxonomy() {
        // Uniform reservoir sampling is the equal-weight (unweighted) case of without-replacement
        // sampling; Hübschle-Schneider and Sanders classify it as WRS-N, not WRS-R (see §2.3).
        return Taxonomy.WRS_N;
    }
}
