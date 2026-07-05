package com.vladmihalcea.phd.reservoir.jmh;

import com.vladmihalcea.phd.reservoir.common.util.AbstractBenchmarkTest;

import java.util.concurrent.TimeUnit;

/**
 * @author Vlad Mihalcea
 */
public class SanityBenchmarkTest extends AbstractBenchmarkTest {

    @Override
    protected Class<?> benchmarkClass() {
        return SanityBenchmark.class;
    }

    @Override
    protected TimeUnit timeUnit() {
        return TimeUnit.NANOSECONDS;
    }

    @Override
    protected int warmupIterations() {
        return 1;
    }

    @Override
    protected int measurementIterations() {
        return 1;
    }

    @Override
    protected int iterationSeconds() {
        return 1;
    }

    @Override
    protected int forks() {
        return 1;
    }
}
