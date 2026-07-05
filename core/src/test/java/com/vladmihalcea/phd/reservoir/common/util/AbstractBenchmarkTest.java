package com.vladmihalcea.phd.reservoir.common.util;

import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * JUnit entry point that runs a JMH benchmark class through the failsafe plugin, mirroring the
 * dto-entity-auto-sync convention (a {@code *BenchmarkTest} wrapper per {@code *Benchmark}). Results are
 * additionally persisted as JSON under {@code metrics/} so the {@code ResultsToCsv} utility and the
 * gnuplot scripts can consume them.
 *
 * @author Vlad Mihalcea
 */
public abstract class AbstractBenchmarkTest {

    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());

    protected abstract Class<?> benchmarkClass();

    // Defaults are publication-grade; a quick pass can shrink them, e.g. -Djmh.forks=1 -Djmh.iters=1 -Djmh.secs=1.
    protected int warmupIterations() {
        return Integer.getInteger("jmh.warmup", 5);
    }

    protected int measurementIterations() {
        return Integer.getInteger("jmh.iters", 10);
    }

    protected int iterationSeconds() {
        return Integer.getInteger("jmh.secs", 2);
    }

    protected int forks() {
        return Integer.getInteger("jmh.forks", 2);
    }

    /** Sub-classes may narrow the time unit; per-update benchmarks override to nanoseconds. */
    protected TimeUnit timeUnit() {
        return TimeUnit.MICROSECONDS;
    }

    /**
     * When non-null, the benchmark is run once per thread count and each run is stored under a
     * thread-count-suffixed result file. Used by the concurrency experiment (E2).
     */
    protected int[] threadSweep() {
        return null;
    }

    /**
     * Whether to attach JMH's {@link GCProfiler}, which reports {@code gc.alloc.rate.norm} (bytes
     * allocated per operation) — the per-update allocation-churn half of the E4 memory figure. Enabled
     * globally with {@code -Djmh.profGc=true} (the {@code UpdateThroughputBenchmarkTest} turns it on so
     * the survey's allocation-rate chart is produced alongside the throughput numbers).
     */
    protected boolean profileAllocations() {
        return Boolean.getBoolean("jmh.profGc");
    }

    @Test
    public void runBenchmarks() throws RunnerException {
        int[] sweep = threadSweep();
        if (sweep == null) {
            Collection<RunResult> results = execute(1, resultFile(""));
            writeCsvSummary(results, "");
            LOGGER.info("Completed {} result(s) for {}", results.size(), benchmarkClass().getSimpleName());
        } else {
            for (int threads : sweep) {
                Collection<RunResult> results = execute(threads, resultFile("-t" + threads));
                writeCsvSummary(results, "-t" + threads);
                LOGGER.info("Completed {} result(s) for {} @ {} thread(s)",
                        results.size(), benchmarkClass().getSimpleName(), threads);
            }
        }
    }

    /**
     * Emits a tidy long-format CSV (one row per benchmark result, with every JMH {@code @Param} as its
     * own column) straight from the {@link RunResult}s, so no JSON post-processing step is needed before
     * the gnuplot scripts consume it.
     */
    private void writeCsvSummary(Collection<RunResult> results, String suffix) {
        if (results.isEmpty()) {
            return;
        }
        Set<String> paramKeys = new LinkedHashSet<>();
        for (RunResult result : results) {
            if (result.getParams().getParamsKeys() != null) {
                paramKeys.addAll(result.getParams().getParamsKeys());
            }
        }
        List<String> header = new ArrayList<>();
        header.add("benchmark");
        header.add("mode");
        header.addAll(paramKeys);
        header.add("threads");
        header.add("score");
        header.add("score_error");
        header.add("unit");
        header.add("alloc_rate_norm");

        Path csv = CsvWriter.metricsPath("jmh-results", benchmarkClass().getSimpleName() + suffix + ".csv");
        try (CsvWriter out = new CsvWriter(csv, header.toArray(new String[0]))) {
            for (RunResult result : results) {
                List<Object> row = new ArrayList<>();
                String fqn = result.getParams().getBenchmark();
                row.add(fqn.substring(fqn.lastIndexOf('.') + 1));
                row.add(result.getParams().getMode().shortLabel());
                for (String key : paramKeys) {
                    row.add(result.getParams().getParam(key));
                }
                row.add(result.getParams().getThreads());
                row.add(result.getPrimaryResult().getScore());
                row.add(result.getPrimaryResult().getScoreError());
                row.add(result.getPrimaryResult().getScoreUnit());
                row.add(allocRateNorm(result));
                out.writeRow(row.toArray());
            }
        }
        LOGGER.info("Wrote JMH summary CSV to {}", csv);
    }

    /**
     * The {@code gc.alloc.rate.norm} secondary result (bytes/op) when the GC profiler ran, else blank.
     */
    private static String allocRateNorm(RunResult result) {
        Map<String, Result> secondary = result.getSecondaryResults();
        Result alloc = secondary == null ? null : secondary.get("gc.alloc.rate.norm");
        return alloc == null ? "" : String.format(java.util.Locale.ROOT, "%.3f", alloc.getScore());
    }

    private Collection<RunResult> execute(int threads, Path resultFile) throws RunnerException {
        OptionsBuilder builder = new OptionsBuilder();
        builder.include(benchmarkClass().getSimpleName() + "\\..*")
                .warmupIterations(warmupIterations())
                .warmupTime(TimeValue.seconds(iterationSeconds()))
                .measurementIterations(measurementIterations())
                .measurementTime(TimeValue.seconds(iterationSeconds()))
                .timeUnit(timeUnit())
                .threads(threads)
                .forks(forks())
                .shouldFailOnError(true);
        if (profileAllocations()) {
            builder.addProfiler(GCProfiler.class);
        }
        if (resultFile != null) {
            builder.resultFormat(ResultFormatType.JSON)
                    .result(resultFile.toString());
        }
        Options opts = builder.build();
        return new Runner(opts).run();
    }

    private Path resultFile(String suffix) {
        try {
            Path base = Path.of("").toAbsolutePath();
            Path metrics = base.resolve("metrics");
            if (!Files.exists(metrics) && base.getParent() != null) {
                metrics = base.getParent().resolve("metrics");
            }
            Path jmhResults = metrics.resolve("jmh-results");
            Files.createDirectories(jmhResults);
            return jmhResults.resolve(benchmarkClass().getSimpleName() + suffix + ".json");
        } catch (Exception e) {
            LOGGER.warn("Could not prepare JMH result file, continuing without it", e);
            return null;
        }
    }
}
