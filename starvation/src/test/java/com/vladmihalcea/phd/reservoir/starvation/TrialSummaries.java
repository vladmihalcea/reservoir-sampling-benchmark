package com.vladmihalcea.phd.reservoir.starvation;

import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.util.SampleSummary;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Collects the repeated trials of the starvation experiments (V1, V3, V4) per configuration and writes one summary row
 * per configuration: for every metric, the median, the mean with its 95% Student-t interval, and the minimum and
 * maximum over the trials. A single 10 s window per configuration, as the November 14 campaign ran, is not enough:
 * the 33 identical V3 trials of that campaign (HikariCP, 10,000 virtual threads) ranged from 2,001 to 12,184
 * acquisition timeouts and from 10 to 1,616 clients that completed nothing, so the summaries report the spread next to
 * the center, and the per-trial rows stay in their own CSV.
 *
 * @author Vlad Mihalcea
 */
final class TrialSummaries {

    /** A configuration: everything that identifies a row of the results except the trial. */
    record Key(String pool, int size, String kind, int clients, String admission, String recorder) {
    }

    private record Metric(String name, ToDoubleFunction<StarvationDriver.Result> value) {
    }

    private static final List<Metric> METRICS = List.of(
            new Metric("tps", StarvationDriver.Result::throughput),
            new Metric("acquisition_timeouts", r -> r.acquisitionFailures()),
            new Metric("acquire_p50_ms", r -> ms(r.acquire().p50())),
            new Metric("acquire_p99_ms", r -> ms(r.acquire().p99())),
            new Metric("acquire_p999_ms", r -> ms(r.acquire().p999())),
            new Metric("acquire_max_ms", r -> ms(r.acquire().max())),
            new Metric("wait_p50_ms", r -> ms(r.totalWait().p50())),
            new Metric("wait_p99_ms", r -> ms(r.totalWait().p99())),
            new Metric("wait_p999_ms", r -> ms(r.totalWait().p999())),
            new Metric("wait_max_ms", r -> ms(r.totalWait().max())),
            new Metric("jain", StarvationDriver.Result::jainFairness),
            new Metric("starved_clients", r -> r.starvedClients()),
            new Metric("cpu_util", StarvationDriver.Result::cpuUtilization),
            new Metric("rec_p99_ms", r -> ms(r.recorderP99())),
            new Metric("rec_p999_ms", r -> ms(r.recorderP999())),
            new Metric("rec_max_ms", r -> ms(r.recorderMax())));

    private static final String[] STATS = {"median", "mean", "ci95", "min", "max"};

    private final Map<Key, List<StarvationDriver.Result>> trials = new LinkedHashMap<>();

    /**
     * Keeps the result's metrics, not its acquisition trace: the virtual-thread experiment runs 400 windows of 60 s,
     * and their traces filled the 8 GB heap in the third trial.
     */
    void add(Key key, StarvationDriver.Result result) {
        trials.computeIfAbsent(key, k -> new ArrayList<>()).add(result.withoutTrace());
    }

    List<StarvationDriver.Result> results(Key key) {
        return trials.getOrDefault(key, List.of());
    }

    /** The summary of one metric of one configuration (for tests that compare configurations, such as V3). */
    SampleSummary summary(Key key, String metric) {
        for (Metric m : METRICS) {
            if (m.name().equals(metric)) {
                SampleSummary summary = new SampleSummary();
                for (StarvationDriver.Result result : results(key)) {
                    summary.add(m.value().applyAsDouble(result));
                }
                return summary;
            }
        }
        throw new IllegalArgumentException("Unknown metric: " + metric);
    }

    static String[] header() {
        List<String> header = new ArrayList<>(List.of("pool", "pool_size", "thread_kind", "clients", "admission",
                "recorder", "trials"));
        for (Metric metric : METRICS) {
            for (String stat : STATS) {
                header.add(metric.name() + "_" + stat);
            }
        }
        return header.toArray(String[]::new);
    }

    /** Writes one row per configuration, in the order the configurations were first measured. */
    void write(Path csv) {
        try (CsvWriter out = new CsvWriter(csv, header())) {
            for (Map.Entry<Key, List<StarvationDriver.Result>> entry : trials.entrySet()) {
                Key key = entry.getKey();
                List<Object> row = new ArrayList<>(List.of(key.pool(), key.size(), key.kind(), key.clients(),
                        key.admission(), key.recorder(), entry.getValue().size()));
                for (Metric metric : METRICS) {
                    SampleSummary summary = new SampleSummary();
                    for (StarvationDriver.Result result : entry.getValue()) {
                        summary.add(metric.value().applyAsDouble(result));
                    }
                    row.add(summary.median());
                    row.add(summary.mean());
                    row.add(summary.tCi95HalfWidth());
                    row.add(summary.min());
                    row.add(summary.max());
                }
                out.writeRow(row.toArray());
            }
        }
    }

    private static double ms(double nanos) {
        return nanos / 1e6;
    }
}
