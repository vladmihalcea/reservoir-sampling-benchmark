package com.vladmihalcea.phd.reservoir.impl;

import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;
import com.vladmihalcea.phd.reservoir.util.Quantiles;

import java.util.SplittableRandom;

/**
 * Efraimidis-Spirakis A-ExpJ (2006): the exponential-jumps variant of {@link EfraimidisSpirakisARes}.
 * Rather than computing a key for every element, it draws a jump in "weight space" from the current
 * eviction threshold {@code T_w} (the smallest retained key) and skips over elements until their
 * cumulative weight exceeds the jump, at which point one element is admitted and a fresh jump drawn.
 * <p>
 * This is the same skip optimization as Algorithm L but for the <em>weighted</em> without-replacement
 * setting, and it is the direct single-threaded predecessor of Hübschle-Schneider's batched WRS-B.
 *
 * @author Vlad Mihalcea
 */
public class EfraimidisSpirakisAExpJ implements SamplingStrategy {

    private final int k;
    private final SplittableRandom random;
    private final double[] heapKeys;
    private final long[] heapValues;
    private int size;
    private long count;
    private double jumpWeight;

    public EfraimidisSpirakisAExpJ(int k, long seed) {
        this.k = k;
        this.random = new SplittableRandom(seed);
        this.heapKeys = new double[k];
        this.heapValues = new long[k];
    }

    @Override
    public void update(long value) {
        update(value, 1.0);
    }

    public void update(long value, double weight) {
        count++;
        if (size < k) {
            double key = Math.pow(nextPositiveDouble(), 1.0 / weight);
            heapKeys[size] = key;
            heapValues[size] = value;
            size++;
            if (size == k) {
                buildHeap();
                drawJump();
            }
            return;
        }
        jumpWeight -= weight;
        if (jumpWeight <= 0.0) {
            double threshold = heapKeys[0];
            double lower = Math.pow(threshold, weight);
            double r = lower + random.nextDouble() * (1.0 - lower);
            heapKeys[0] = Math.pow(r, 1.0 / weight);
            heapValues[0] = value;
            siftDown(0);
            drawJump();
        }
    }

    private void drawJump() {
        double threshold = heapKeys[0];
        jumpWeight = Math.log(nextPositiveDouble()) / Math.log(threshold);
    }

    private double nextPositiveDouble() {
        double u = random.nextDouble();
        return u <= 0.0 ? Double.MIN_VALUE : u;
    }

    private void buildHeap() {
        for (int i = (size >>> 1) - 1; i >= 0; i--) {
            siftDown(i);
        }
    }

    private void siftDown(int i) {
        int half = size >>> 1;
        while (i < half) {
            int child = (i << 1) + 1;
            int right = child + 1;
            if (right < size && heapKeys[right] < heapKeys[child]) {
                child = right;
            }
            if (heapKeys[i] <= heapKeys[child]) {
                break;
            }
            double tk = heapKeys[i];
            heapKeys[i] = heapKeys[child];
            heapKeys[child] = tk;
            long tv = heapValues[i];
            heapValues[i] = heapValues[child];
            heapValues[child] = tv;
            i = child;
        }
    }

    @Override
    public double valueAtQuantile(double q) {
        return Quantiles.valueAtQuantile(heapValues, size, q);
    }

    @Override
    public void reset() {
        size = 0;
        count = 0;
        jumpWeight = 0.0;
    }

    @Override
    public String id() {
        return "ES-A-ExpJ(k=" + k + ")";
    }

    @Override
    public Taxonomy taxonomy() {
        return Taxonomy.WRS_N;
    }
}
