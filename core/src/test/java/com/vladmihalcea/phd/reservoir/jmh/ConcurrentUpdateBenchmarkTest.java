package com.vladmihalcea.phd.reservoir.jmh;

import com.vladmihalcea.phd.reservoir.common.util.AbstractBenchmarkTest;

/**
 * @author Vlad Mihalcea
 */
public class ConcurrentUpdateBenchmarkTest extends AbstractBenchmarkTest {

    @Override
    protected Class<?> benchmarkClass() {
        return ConcurrentUpdateBenchmark.class;
    }

    @Override
    protected int[] threadSweep() {
        return new int[]{1, 2, 4, 8, 16};
    }
}
