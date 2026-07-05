package com.vladmihalcea.phd.reservoir.jmh;

import com.vladmihalcea.phd.reservoir.common.util.AbstractBenchmarkTest;

import java.util.concurrent.TimeUnit;

/**
 * @author Vlad Mihalcea
 */
public class QuantileQueryBenchmarkTest extends AbstractBenchmarkTest {

    @Override
    protected Class<?> benchmarkClass() {
        return QuantileQueryBenchmark.class;
    }

    @Override
    protected TimeUnit timeUnit() {
        return TimeUnit.NANOSECONDS;
    }
}
