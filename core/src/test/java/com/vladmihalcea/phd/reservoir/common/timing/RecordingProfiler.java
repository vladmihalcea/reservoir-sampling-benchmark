package com.vladmihalcea.phd.reservoir.common.timing;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ScalarResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * JMH profiler that reports, as secondary results of every iteration, what a shared latency recorder did to the
 * requests of that iteration ({@link RecordingTimings}): the mean latency of its {@code update} call
 * ({@code rec.update.mean.ns}), the mean response time of the requests ({@code rec.response.mean.ms}), the scrape
 * time, and the process CPU load; the percentiles of the whole measurement go to the benchmark's own side output. JMH then aggregates them over the iterations and forks with
 * their confidence intervals, next to the throughput. The benchmark publishes the values in its iteration tear-down,
 * which JMH runs before this profiler's {@link #afterIteration}.
 * <p>
 * Usage: {@code -prof com.vladmihalcea.phd.reservoir.common.timing.RecordingProfiler}
 *
 * @author Vlad Mihalcea
 */
public class RecordingProfiler implements InternalProfiler {

    @Override
    public String getDescription() {
        return "Recorder update latency, request response time, scrape time, and CPU load per iteration";
    }

    @Override
    public void beforeIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams) {
        RecordingTimings.take();
    }

    @Override
    public Collection<? extends Result> afterIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams,
                                                       IterationResult result) {
        List<Result> results = new ArrayList<>();
        for (RecordingTimings.Measurement m : RecordingTimings.take()) {
            if (!Double.isNaN(m.value())) {
                results.add(new ScalarResult(m.label(), m.value(), m.unit(),
                        m.max() ? AggregationPolicy.MAX : AggregationPolicy.AVG));
            }
        }
        return results;
    }
}
