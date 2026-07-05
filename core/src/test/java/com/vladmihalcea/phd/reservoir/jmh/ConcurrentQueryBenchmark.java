package com.vladmihalcea.phd.reservoir.jmh;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.workload.SyntheticWorkloads;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Experiment E5 (concurrent half): cost of reading p99 <em>while updates continue</em>. The idle-query
 * baseline in {@link QuantileQueryBenchmark} understates real cost, because a live metrics endpoint reads
 * quantiles from a structure other threads are still writing to — snapshot copies, lock hand-offs and
 * cache traffic all appear only under concurrency. Four writer threads hammer {@code update} while one
 * reader thread times {@code valueAtQuantile(0.99)}; JMH reports the reader and writer scores separately.
 * <p>
 * Restricted to the strategies that tolerate concurrent update+query without an external lock
 * ({@link StrategyCatalog#isThreadSafe}); the single-writer reservoirs are covered by E2 instead.
 *
 * @author Vlad Mihalcea
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Group)
public class ConcurrentQueryBenchmark {

    @Param({"Dropwizard-Uniform", "Dropwizard-ExpDecay", "Micrometer-Summary",
            "Prometheus-CKMS", "Prometheus-Native"})
    public String strategy;

    private static final int VALUE_COUNT = 1 << 16;
    private long[] values;
    private SamplingStrategy sampler;

    @Setup(Level.Trial)
    public void setup() {
        values = SyntheticWorkloads.lognormal().generate(VALUE_COUNT, 2027L);
        sampler = StrategyCatalog.create(strategy, StrategyCatalog.DEFAULT_K, Clock.SYSTEM, 1L);
        // Warm the structure so the reader sees a realistic, populated snapshot from the first query.
        for (long value : values) {
            sampler.update(value);
        }
    }

    @Benchmark
    @Group("readWrite")
    @GroupThreads(4)
    public void writer() {
        sampler.update(values[ThreadLocalRandom.current().nextInt(VALUE_COUNT)]);
    }

    @Benchmark
    @Group("readWrite")
    @GroupThreads(1)
    public double reader() {
        return sampler.valueAtQuantile(0.99);
    }
}
