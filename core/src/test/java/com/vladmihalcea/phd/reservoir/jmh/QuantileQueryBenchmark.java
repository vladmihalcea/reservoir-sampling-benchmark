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

import java.util.concurrent.TimeUnit;

/**
 * Experiment E5: cost of reading the p99 from a warm strategy. Sampling reservoirs pay a sort at query
 * time, HdrHistogram/Prometheus pay a bucket scan, and CKMS-style rank summaries pay at record time
 * instead — so a fair "total cost of ownership" comparison must measure query cost separately from
 * update cost. The strategy is pre-filled once per trial and then queried in the timed loop.
 *
 * @author Vlad Mihalcea
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class QuantileQueryBenchmark {

    @Param({"Vitter-R", "ES-A-ExpJ", "WRS-B", "Dropwizard-Uniform", "Dropwizard-ExpDecay",
            "Micrometer-Summary", "Prometheus-CKMS", "Prometheus-Native", "Prometheus-Histogram",
            "HdrHistogram", "VictoriaMetrics"})
    public String strategy;

    private SamplingStrategy sampler;

    @Setup(Level.Trial)
    public void setup() {
        sampler = StrategyCatalog.create(strategy, StrategyCatalog.DEFAULT_K, Clock.SYSTEM, 1L);
        long[] values = SyntheticWorkloads.lognormal().generate(1 << 20, 2027L);
        for (long value : values) {
            sampler.update(value);
        }
    }

    @Benchmark
    public double queryP99() {
        return sampler.valueAtQuantile(0.99);
    }
}
