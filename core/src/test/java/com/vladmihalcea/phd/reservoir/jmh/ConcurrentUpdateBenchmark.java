package com.vladmihalcea.phd.reservoir.jmh;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
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
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Experiment E2: aggregate update throughput under contention. Production metrics libraries share one
 * timer across all request threads, so the reservoir is a contended structure. Each strategy is measured
 * under the concurrency regime it is actually deployed with:
 * <ul>
 *   <li>the single-writer reference and clean-room reservoirs (Vitter, Efraimidis-Spirakis, WRS-B,
 *       HdrHistogram, the clean-room histogram) are <strong>not</strong> thread-safe, so every update is
 *       serialised by one external {@link ReentrantLock} — the "one global lock around a single-threaded
 *       reservoir" pattern the survey criticises;</li>
 *   <li>the production frameworks that synchronise internally or are lock-free (Dropwizard reservoirs,
 *       Micrometer, the Prometheus client metrics) run <strong>unguarded</strong>, so the benchmark sees
 *       their own concurrency mechanism instead of an artificial lock.</li>
 * </ul>
 * Uniformly locking every strategy (as an earlier version did) would erase exactly the contrast this
 * experiment exists to show. The regime is chosen per strategy from {@link StrategyCatalog#isThreadSafe}.
 * The thread count is swept by the test wrapper (1..16) via JMH's {@code threads} option.
 *
 * @author Vlad Mihalcea
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class ConcurrentUpdateBenchmark {

    @Param({"Vitter-R", "ES-A-ExpJ", "WRS-B", "HdrHistogram", "Prometheus-Histogram",
            "Dropwizard-Uniform", "Dropwizard-ExpDecay", "Micrometer-Summary",
            "Prometheus-CKMS", "Prometheus-Native"})
    public String strategy;

    private static final int VALUE_COUNT = 1 << 16;
    private long[] values;
    private SamplingStrategy sampler;
    private boolean lockFree;
    private final ReentrantLock lock = new ReentrantLock();

    @Setup(Level.Trial)
    public void setup() {
        values = SyntheticWorkloads.lognormal().generate(VALUE_COUNT, 2025L);
        sampler = StrategyCatalog.create(strategy, StrategyCatalog.DEFAULT_K, Clock.SYSTEM, 1L);
        lockFree = StrategyCatalog.isThreadSafe(strategy);
    }

    @Benchmark
    public void update(Blackhole blackhole) {
        long value = values[ThreadLocalRandom.current().nextInt(VALUE_COUNT)];
        if (lockFree) {
            sampler.update(value);
        } else {
            lock.lock();
            try {
                sampler.update(value);
            } finally {
                lock.unlock();
            }
        }
        blackhole.consume(sampler);
    }
}
