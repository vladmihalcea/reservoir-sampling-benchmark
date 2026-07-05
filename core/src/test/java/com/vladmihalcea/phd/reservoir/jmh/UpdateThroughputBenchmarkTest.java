package com.vladmihalcea.phd.reservoir.jmh;

import com.vladmihalcea.phd.reservoir.common.util.AbstractBenchmarkTest;

import java.util.concurrent.TimeUnit;

/**
 * @author Vlad Mihalcea
 */
public class UpdateThroughputBenchmarkTest extends AbstractBenchmarkTest {

    @Override
    protected Class<?> benchmarkClass() {
        return UpdateThroughputBenchmark.class;
    }

    @Override
    protected TimeUnit timeUnit() {
        return TimeUnit.NANOSECONDS;
    }
}
