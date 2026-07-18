package com.vladmihalcea.phd.reservoir.impl;

import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;
import com.vladmihalcea.phd.reservoir.util.Quantiles;

import java.util.SplittableRandom;

/**
 * Batched reservoir sampling: the WRS-B <em>problem</em> of Hübschle-Schneider and Sanders (ACM TOMS
 * 2022) — repeatedly solving without-replacement selection as batches of new items arrive, storing
 * only the current sample and the batch. This is a single-threaded realization: it keeps the exact
 * same exponential-jump acceptance rule as {@link EfraimidisSpirakisAExpJ}, but consumes the input in
 * batches, carrying the jump budget across a primitive {@code long[]} buffer processed in a tight
 * loop, so most elements are skipped with a single subtraction and no per-element virtual dispatch,
 * heap touch or random draw.
 * <p>
 * This is NOT the paper's Section 9 algorithm, which is distributed (local B+-tree reservoirs across
 * processing elements combined by a multisequence distributed selection). The ~55x figure reported in
 * that paper is for parallel alias-table construction (WRS-1, 158 threads), not sequential batched
 * reservoir sampling. The speedup this class shows over per-element Efraimidis-Spirakis comes from
 * sequential batching alone and is measured directly by the batched-insertion benchmark (E3). With
 * unit weights it is the equal-weight (unweighted) case of WRS-B.
 * <p>
 * The streaming {@link #update(long)} path buffers into {@code batchSize} chunks; the
 * {@link #updateBatch(long[], int)} entry point lets E3 feed whole arrays and measure the speedup.
 *
 * @author Vlad Mihalcea
 */
public class WrsBBatched implements SamplingStrategy {

    private final int k;
    private final int batchSize;
    private final SplittableRandom random;

    private final double[] heapKeys;
    private final long[] heapValues;
    private int size;

    private final long[] buffer;
    private int bufferLength;

    private double jumpWeight;
    private boolean jumpPrimed;

    public WrsBBatched(int k, int batchSize, long seed) {
        this.k = k;
        this.batchSize = batchSize;
        this.random = new SplittableRandom(seed);
        this.heapKeys = new double[k];
        this.heapValues = new long[k];
        this.buffer = new long[batchSize];
    }

    @Override
    public void update(long value) {
        if (size < k) {
            admitInitial(value);
            return;
        }
        buffer[bufferLength++] = value;
        if (bufferLength == batchSize) {
            drainBuffer();
        }
    }

    /**
     * Bulk entry point: apply a whole array (its first {@code length} entries) in one batched pass.
     */
    public void updateBatch(long[] values, int length) {
        int i = 0;
        while (size < k && i < length) {
            admitInitial(values[i++]);
        }
        if (i < length) {
            applyBatch(values, i, length);
        }
    }

    private void admitInitial(long value) {
        double key = Math.pow(nextPositiveDouble(), 1.0);
        heapKeys[size] = key;
        heapValues[size] = value;
        size++;
        if (size == k) {
            buildHeap();
        }
    }

    private void drainBuffer() {
        applyBatch(buffer, 0, bufferLength);
        bufferLength = 0;
    }

    /**
     * Core batched acceptance loop over {@code values[from..to)} with unit weights.
     */
    private void applyBatch(long[] values, int from, int to) {
        int i = from;
        while (i < to) {
            if (!jumpPrimed) {
                drawJump();
            }
            long need = (long) Math.ceil(jumpWeight);
            if (need < 1) {
                need = 1;
            }
            long admitIndex = i + need - 1;
            if (admitIndex >= to) {
                // Admission point falls beyond this batch: carry the remaining budget forward.
                jumpWeight -= (to - i);
                return;
            }
            double threshold = heapKeys[0];
            double r = threshold + random.nextDouble() * (1.0 - threshold);
            heapKeys[0] = r;
            heapValues[0] = values[(int) admitIndex];
            siftDown(0);
            jumpPrimed = false;
            i = (int) admitIndex + 1;
        }
    }

    private void drawJump() {
        jumpWeight = Math.log(nextPositiveDouble()) / Math.log(heapKeys[0]);
        jumpPrimed = true;
    }

    private double nextPositiveDouble() {
        double u = random.nextDouble();
        return u <= 0.0 ? Double.MIN_VALUE : u;
    }

    private void buildHeap() {
        for (int j = (size >>> 1) - 1; j >= 0; j--) {
            siftDown(j);
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
        if (bufferLength > 0) {
            drainBuffer();
        }
        return Quantiles.valueAtQuantile(heapValues, size, q);
    }

    @Override
    public void reset() {
        size = 0;
        bufferLength = 0;
        jumpPrimed = false;
        jumpWeight = 0.0;
    }

    @Override
    public String id() {
        return "WRS-B(k=" + k + ",b=" + batchSize + ")";
    }

    @Override
    public Taxonomy taxonomy() {
        return Taxonomy.WRS_B;
    }
}
