package com.vladmihalcea.phd.reservoir.accuracy;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.util.Proportions;
import com.vladmihalcea.phd.reservoir.util.SampleSummary;
import com.vladmihalcea.phd.reservoir.workload.AcquisitionTraces;
import com.vladmihalcea.phd.reservoir.workload.WorkloadTrace;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Experiment V2 of the virtual-threads follow-up: how far the production recorders
 * ({@link StrategyCatalog#PRODUCTION_RECORDERS}) misreport the tail of connection acquisition under pool
 * starvation. Each lossless acquisition trace captured by the starvation module's driver is
 * replayed, in completion order and on a manual clock that follows the recorded timestamps, into a fresh
 * instance of every recorder; the estimates read at the end of the run (as a dashboard would read them)
 * are scored against the exact quantiles of the same trace. Micrometer follows the trace's clock too
 * ({@link StrategyCatalog#createOnClock}), so its percentile window rotates as it would have live.
 * <p>
 * Dropwizard's reservoirs sample with an unseeded generator ({@link StrategyCatalog#UNSEEDED}), so one replay is one
 * draw: whether a fixed-size reservoir shows a rare timeout is a matter of chance, which a single replay reports as a
 * yes or a no. Each of them is therefore replayed {@code rs.tail.replays} (100) times; the deterministic recorders
 * once. Besides the relative error at p50, p99 and p99.9 (the mean over the replays), the experiment reports the
 * maximum, the value that reveals an acquisition stuck until the timeout ({@link StrategyCatalog#reportedTop}): for
 * Micrometer its own decaying max, for the Prometheus summary its highest tracked quantile (it keeps no maximum), and
 * the largest retained sample otherwise. A trace whose true maximum reached {@code rs.acquisitionTimeoutNanos}
 * (5 s by default) counts as one with a timeout, and a replay shows it when the reported maximum reaches 90% of the
 * timeout.
 * <p>
 * Outputs ({@code metrics/AcquisitionTail/}): {@code tail-accuracy.csv} (long form), {@code tail-p999-error.csv} and
 * {@code tail-max-ratio.csv} (wide, the means over the replays), {@code tail-timeouts.csv}, and
 * {@code tail-detection.csv}: per trace and recorder, the share of the replays that showed a timeout with its 95%
 * Wilson interval, next to the probability that a uniform reservoir of the same size retains at least one of the
 * trace's timeouts ({@link Proportions#uniformReservoirHit}), the model the replays are checked against.
 * <p>
 * Skipped when no traces exist yet; produce them with the starvation module's
 * {@code VirtualThreadPoolExperimentTest}, or point {@code -Drs.acquisitionTraces} at another folder.
 *
 * @author Vlad Mihalcea
 */
public class AcquisitionTailAccuracyExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(AcquisitionTailAccuracyExperimentTest.class);

    private static final double[] QUANTILES = {0.5, 0.99, 0.999};
    private static final long TIMEOUT_NANOS = Long.getLong("rs.acquisitionTimeoutNanos", 5_000_000_000L);
    private static final int REPLAYS = Integer.getInteger("rs.tail.replays", 100);

    @Test
    public void measureAcquisitionTailAccuracy() {
        List<Path> files = AcquisitionTraces.files(AcquisitionTraces.directory());
        Assumptions.assumeFalse(files.isEmpty(),
                "No acquisition traces in " + AcquisitionTraces.directory().toAbsolutePath());

        List<String> recorders = StrategyCatalog.PRODUCTION_RECORDERS;
        String[] wideHeader = new String[recorders.size() + 1];
        wideHeader[0] = "trace";
        for (int i = 0; i < recorders.size(); i++) {
            wideHeader[i + 1] = recorders.get(i);
        }

        try (CsvWriter longForm = new CsvWriter(CsvWriter.metricsPath("AcquisitionTail", "tail-accuracy.csv"),
                "trace", "samples", "recorder", "replays", "quantile", "exact_ns", "estimate_mean_ns", "rel_error_mean",
                "rel_error_ci95", "rel_error_min", "rel_error_max");
             CsvWriter p999 = new CsvWriter(CsvWriter.metricsPath("AcquisitionTail", "tail-p999-error.csv"), wideHeader);
             CsvWriter max = new CsvWriter(CsvWriter.metricsPath("AcquisitionTail", "tail-max-ratio.csv"), wideHeader);
             CsvWriter timeouts = new CsvWriter(CsvWriter.metricsPath("AcquisitionTail", "tail-timeouts.csv"),
                     "trace", "recorder", "replays", "true_timeouts", "true_max_ns", "reported_max_mean_ns",
                     "reported_max_min_ns", "timeout_visible_replays");
             CsvWriter detection = new CsvWriter(CsvWriter.metricsPath("AcquisitionTail", "tail-detection.csv"),
                     "trace", "samples", "true_timeouts", "timeout_share", "recorder", "replays", "visible_replays",
                     "visible_share", "visible_ci95_low", "visible_ci95_high", "uniform_model_visible",
                     "max_ratio_mean", "max_ratio_min", "p999_error_mean", "p999_error_p90")) {
            for (Path file : files) {
                WorkloadTrace trace = AcquisitionTraces.loadFile(file);
                long[] values = trace.values();
                long[] timestamps = trace.timestampsNanos();
                long[] sorted = values.clone();
                Arrays.sort(sorted);
                double[] exact = new double[QUANTILES.length];
                for (int i = 0; i < QUANTILES.length; i++) {
                    exact[i] = ExactQuantileBaseline.ofSorted(sorted, sorted.length, QUANTILES[i]);
                }
                long trueMax = sorted.length == 0 ? 0 : sorted[sorted.length - 1];
                int trueTimeouts = 0;
                for (long v : values) {
                    if (v >= TIMEOUT_NANOS) {
                        trueTimeouts++;
                    }
                }

                Object[] p999Row = new Object[recorders.size() + 1];
                Object[] maxRow = new Object[recorders.size() + 1];
                p999Row[0] = trace.name();
                maxRow[0] = trace.name();
                for (int r = 0; r < recorders.size(); r++) {
                    String name = recorders.get(r);
                    int replays = StrategyCatalog.UNSEEDED.contains(name) ? REPLAYS : 1;
                    SampleSummary[] errors = new SampleSummary[QUANTILES.length];
                    SampleSummary[] estimates = new SampleSummary[QUANTILES.length];
                    for (int i = 0; i < QUANTILES.length; i++) {
                        errors[i] = new SampleSummary();
                        estimates[i] = new SampleSummary();
                    }
                    SampleSummary reportedMax = new SampleSummary();
                    SampleSummary maxRatio = new SampleSummary();
                    SampleSummary maxError = new SampleSummary();
                    int visible = 0;
                    for (int replay = 0; replay < replays; replay++) {
                        Clock.Manual clock = new Clock.Manual();
                        SamplingStrategy recorder = StrategyCatalog.createOnClock(
                                name, StrategyCatalog.DEFAULT_K, clock, StrategyCatalog.DEFAULT_SEED);
                        for (int i = 0; i < values.length; i++) {
                            clock.set(timestamps[i]);
                            recorder.update(values[i], timestamps[i]);
                        }
                        for (int i = 0; i < QUANTILES.length; i++) {
                            double estimate = recorder.valueAtQuantile(QUANTILES[i]);
                            estimates[i].add(estimate);
                            errors[i].add(ExactQuantileBaseline.relativeError(estimate, exact[i]));
                        }
                        double top = StrategyCatalog.reportedTop(recorder);
                        reportedMax.add(top);
                        maxRatio.add(top / trueMax);
                        maxError.add(ExactQuantileBaseline.relativeError(top, trueMax));
                        if (trueTimeouts > 0 && top >= 0.9 * TIMEOUT_NANOS) {
                            visible++;
                        }
                    }
                    for (int i = 0; i < QUANTILES.length; i++) {
                        longForm.writeRow(trace.name(), values.length, name, replays, QUANTILES[i], exact[i],
                                estimates[i].mean(), errors[i].mean(), errors[i].tCi95HalfWidth(), errors[i].min(),
                                errors[i].max());
                    }
                    longForm.writeRow(trace.name(), values.length, name, replays, 1.0, trueMax, reportedMax.mean(),
                            maxError.mean(), maxError.tCi95HalfWidth(), maxError.min(), maxError.max());
                    p999Row[r + 1] = errors[2].mean();
                    maxRow[r + 1] = maxRatio.mean();
                    timeouts.writeRow(trace.name(), name, replays, trueTimeouts, trueMax, reportedMax.mean(),
                            reportedMax.min(), visible);
                    if (trueTimeouts > 0) {
                        double[] ci = Proportions.wilson95(visible, replays);
                        detection.writeRow(trace.name(), values.length, trueTimeouts,
                                trueTimeouts / (double) values.length, name, replays, visible,
                                visible / (double) replays, ci[0], ci[1],
                                Proportions.uniformReservoirHit(values.length, trueTimeouts, StrategyCatalog.DEFAULT_K),
                                maxRatio.mean(), maxRatio.min(), errors[2].mean(), errors[2].quantile(0.9));
                    }
                }
                p999.writeRow(p999Row);
                max.writeRow(maxRow);
                LOGGER.info("Scored {} recorders on {} ({} acquisitions, exact p99.9={} ms, max={} ms, {} timeouts)",
                        recorders.size(), trace.name(), values.length,
                        String.format("%.2f", exact[2] / 1e6), String.format("%.2f", trueMax / 1e6), trueTimeouts);
            }
        }
    }
}
