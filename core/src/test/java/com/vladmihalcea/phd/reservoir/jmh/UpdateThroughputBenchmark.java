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

import java.util.concurrent.TimeUnit;

/**
 * Experiment E1: single-threaded cost of one {@code update()} per strategy across reservoir sizes.
 * Values are pre-generated so the measured path contains no RNG cost of its own; the strategy's own
 * random draws (the thing we are comparing) remain inside the timed region. Backs the survey's core
 * per-element cost comparison and the "single-threaded Efraimidis-Spirakis is expensive" claim.
 * <p>
 * The {@code k} sweep applies only to the reservoir strategies; the histogram / rank-summary strategies
 * (HdrHistogram, the Prometheus adapters) are size-independent and simply ignore {@code k}, so their four
 * bars are the same by construction — a control, not an omission. Run with {@code -Djmh.profGc=true} to
 * additionally capture {@code gc.alloc.rate.norm} (bytes/update) for the E4 allocation figure.
 *
 * @author Vlad Mihalcea
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class UpdateThroughputBenchmark {

    @Param({"Vitter-R", "Vitter-L-skip", "ES-A-Res", "ES-A-ExpJ", "ForwardDecay-0.015", "WRS-B",
            "Dropwizard-Uniform", "Dropwizard-ExpDecay", "Micrometer-Summary", "Prometheus-CKMS",
            "Prometheus-Native", "Prometheus-Histogram", "HdrHistogram", "VictoriaMetrics"})
    public String strategy;

    @Param({"128", "512", "1028", "4096"})
    public int k;

    private static final int VALUE_COUNT = 1 << 16;
    private long[] values;
    private int cursor;
    private SamplingStrategy sampler;

    @Setup(Level.Trial)
    public void setup() {
        values = SyntheticWorkloads.lognormal().generate(VALUE_COUNT, 2024L);
        sampler = StrategyCatalog.create(strategy, k, Clock.SYSTEM, 1L);
    }

    @Benchmark
    public void update(Blackhole blackhole) {
        long value = values[cursor & (VALUE_COUNT - 1)];
        cursor++;
        sampler.update(value);
        blackhole.consume(sampler);
    }
}
