package com.vladmihalcea.phd.reservoir.jmh;

import com.vladmihalcea.phd.reservoir.impl.VitterR;
import com.vladmihalcea.phd.reservoir.workload.SyntheticWorkloads;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * Trivial single-strategy benchmark used to smoke-test the JMH plumbing end to end (build, run, result
 * file, CSV summary) via {@code run-benchmark.bat Sanity} without paying the full experiment matrix.
 *
 * @author Vlad Mihalcea
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class SanityBenchmark {

    private static final int VALUE_COUNT = 1 << 16;
    private long[] values;
    private int cursor;
    private VitterR reservoir;

    @Setup(Level.Trial)
    public void setup() {
        values = SyntheticWorkloads.lognormal().generate(VALUE_COUNT, 1L);
        reservoir = new VitterR(1028, 1L);
    }

    @Benchmark
    public VitterR update() {
        reservoir.update(values[cursor++ & (VALUE_COUNT - 1)]);
        return reservoir;
    }
}
