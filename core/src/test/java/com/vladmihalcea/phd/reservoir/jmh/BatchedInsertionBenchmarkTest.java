package com.vladmihalcea.phd.reservoir.jmh;

import com.vladmihalcea.phd.reservoir.common.util.AbstractBenchmarkTest;

/**
 * @author Vlad Mihalcea
 */
public class BatchedInsertionBenchmarkTest extends AbstractBenchmarkTest {

    @Override
    protected Class<?> benchmarkClass() {
        return BatchedInsertionBenchmark.class;
    }
}
