package com.vladmihalcea.phd.reservoir.impl;

import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;
import com.vladmihalcea.phd.reservoir.util.Quantiles;

import java.util.SplittableRandom;

/**
 * Efraimidis-Spirakis A-Res (2006): weighted random sampling with a reservoir, keeping the {@code k}
 * items with the largest keys, where each item's key is {@code u^(1/w)} with {@code u} uniform on
 * (0,1) and {@code w} its weight. The reservoir is a binary min-heap keyed on the smallest retained
 * key so that eviction is {@code O(log k)}.
 * <p>
 * A-Res keys on {@code u^(1/w)}; it is the sibling of the priority-sampling scheme (priority
 * {@code w/u}) that Dropwizard's {@code ExponentiallyDecayingReservoir} actually uses under forward
 * decay, not that reservoir's own algorithm. With a constant weight of 1 it degenerates to a uniform
 * sample, the configuration benchmarked against {@link VitterR} for accuracy. It draws one random number and one {@code Math.pow} per
 * element — the per-item cost that batching (WRS-B) removes.
 *
 * @author Vlad Mihalcea
 */
public class EfraimidisSpirakisARes implements SamplingStrategy {

    private final int k;
    private final SplittableRandom random;

    // Parallel min-heap arrays over (key, value); heapKeys[0] is the smallest retained key.
    private final double[] heapKeys;
    private final long[] heapValues;
    private int size;
    private long count;

    public EfraimidisSpirakisARes(int k, long seed) {
        this.k = k;
        this.random = new SplittableRandom(seed);
        this.heapKeys = new double[k];
        this.heapValues = new long[k];
    }

    @Override
    public void update(long value) {
        update(value, 1.0);
    }

    /**
     * Weighted insertion. Used directly by weighted experiments; the unweighted {@link #update(long)}
     * path passes {@code weight = 1.0}.
     */
    public void update(long value, double weight) {
        count++;
        double u = random.nextDouble();
        if (u <= 0.0) {
            u = Double.MIN_VALUE;
        }
        double key = Math.pow(u, 1.0 / weight);
        if (size < k) {
            heapKeys[size] = key;
            heapValues[size] = value;
            size++;
            if (size == k) {
                buildHeap();
            }
        } else if (key > heapKeys[0]) {
            heapKeys[0] = key;
            heapValues[0] = value;
            siftDown(0);
        }
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
            swap(i, child);
            i = child;
        }
    }

    private void swap(int a, int b) {
        double tk = heapKeys[a];
        heapKeys[a] = heapKeys[b];
        heapKeys[b] = tk;
        long tv = heapValues[a];
        heapValues[a] = heapValues[b];
        heapValues[b] = tv;
    }

    @Override
    public double valueAtQuantile(double q) {
        return Quantiles.valueAtQuantile(heapValues, size, q);
    }

    @Override
    public void reset() {
        size = 0;
        count = 0;
    }

    @Override
    public String id() {
        return "ES-A-Res(k=" + k + ")";
    }

    @Override
    public Taxonomy taxonomy() {
        return Taxonomy.WRS_N;
    }
}
