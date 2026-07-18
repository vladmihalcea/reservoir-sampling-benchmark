package com.vladmihalcea.phd.reservoir.jmh;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.framework.DropwizardExpDecay;
import com.vladmihalcea.phd.reservoir.impl.EfraimidisSpirakisARes;
import com.vladmihalcea.phd.reservoir.impl.EfraimidisSpirakisAExpJ;
import com.vladmihalcea.phd.reservoir.impl.WrsBBatched;
import com.vladmihalcea.phd.reservoir.workload.SyntheticWorkloads;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * Experiment E3: bulk-loading a large stream, per-element insertion versus batched WRS-B. This is the
 * figure behind the abstract's "batched reservoir sampling achieves an order-of-magnitude speedup" claim
 * (C4). Three per-element baselines — Efraimidis-Spirakis A-Res, A-ExpJ, and Dropwizard's production
 * {@code ExponentiallyDecayingReservoir} — are contrasted with WRS-B fed through its streaming
 * {@code update()} path at increasing batch sizes, so the speedup can be read as a function of batch
 * granularity (larger batches amortize more per-element dispatch, heap touches and random draws). The
 * per-element legs are batch-size-independent and act as flat controls across the sweep.
 *
 * @author Vlad Mihalcea
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class BatchedInsertionBenchmark {

    @Param({"1000000"})
    public int n;

    /**
     * Batch granularity, consumed only by {@link #batchedWrsB(BatchState)}. Held in its own state class
     * rather than as a class-level {@code @Param} so JMH does not re-run the three batch-size-independent
     * per-element baselines once per batch size; they are flat controls, and multiplying them out cost
     * 18 redundant configurations per run. {@code batchSize=1} is the degenerate no-batching case, which
     * bounds the sweep from below and shows where batching begins to pay.
     */
    @State(Scope.Benchmark)
    public static class BatchState {
        @Param({"1", "10", "100", "1000", "10000", "100000", "1000000"})
        public int batchSize;
    }

    private static final int K = 1028;
    private long[] values;

    @Setup(Level.Trial)
    public void setup() {
        values = SyntheticWorkloads.lognormal().generate(n, 2026L);
    }

    @Benchmark
    public double perElementARes() {
        EfraimidisSpirakisARes reservoir = new EfraimidisSpirakisARes(K, 3L);
        for (long value : values) {
            reservoir.update(value);
        }
        return reservoir.valueAtQuantile(0.99);
    }

    @Benchmark
    public double perElementAExpJ() {
        EfraimidisSpirakisAExpJ reservoir = new EfraimidisSpirakisAExpJ(K, 3L);
        for (long value : values) {
            reservoir.update(value);
        }
        return reservoir.valueAtQuantile(0.99);
    }

    @Benchmark
    public double perElementExpDecay() {
        DropwizardExpDecay reservoir = new DropwizardExpDecay(K, 0.015, Clock.SYSTEM);
        for (long value : values) {
            reservoir.update(value);
        }
        return reservoir.valueAtQuantile(0.99);
    }

    @Benchmark
    public double batchedWrsB(BatchState batch) {
        WrsBBatched reservoir = new WrsBBatched(K, batch.batchSize, 3L);
        for (long value : values) {
            reservoir.update(value);
        }
        return reservoir.valueAtQuantile(0.99);
    }
}
