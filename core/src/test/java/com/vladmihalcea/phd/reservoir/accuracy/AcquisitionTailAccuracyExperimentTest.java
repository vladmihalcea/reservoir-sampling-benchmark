package com.vladmihalcea.phd.reservoir.accuracy;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.framework.MicrometerSummary;
import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.workload.AcquisitionTraces;
import com.vladmihalcea.phd.reservoir.workload.WorkloadTrace;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Experiment V2 of the virtual-threads follow-up: how far the production recorders
 * ({@link StrategyCatalog#PRODUCTION_RECORDERS}) misreport the tail of connection acquisition under pool
 * starvation. Each lossless acquisition trace captured by the starvation module's driver is
 * replayed, in completion order and on a manual clock that follows the recorded timestamps, into a fresh
 * instance of every recorder; the estimates read at the end of the run (as a dashboard would read them)
 * are scored against the exact quantiles of the same trace.
 * <p>
 * Besides the relative error at p50, p99 and p99.9, the experiment reports the maximum, the value that
 * reveals an acquisition stuck until the timeout. Micrometer's maximum is its own decaying max; for the
 * reservoirs it is the largest retained sample. A trace whose true maximum reached
 * {@code rs.acquisitionTimeoutNanos} (5 s by default) counts as one with a timeout, and the summary
 * records whether each recorder's reported maximum shows it (at least 90% of the timeout).
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

    @Test
    public void measureAcquisitionTailAccuracy() {
        List<WorkloadTrace> traces = AcquisitionTraces.load(AcquisitionTraces.directory());
        Assumptions.assumeFalse(traces.isEmpty(),
                "No acquisition traces in " + AcquisitionTraces.directory().toAbsolutePath());

        List<String> recorders = StrategyCatalog.PRODUCTION_RECORDERS;
        String[] wideHeader = new String[recorders.size() + 1];
        wideHeader[0] = "trace";
        for (int i = 0; i < recorders.size(); i++) {
            wideHeader[i + 1] = recorders.get(i);
        }

        try (CsvWriter longForm = new CsvWriter(CsvWriter.metricsPath("AcquisitionTail", "tail-accuracy.csv"),
                "trace", "samples", "recorder", "quantile", "exact_ns", "estimate_ns", "rel_error");
             CsvWriter p999 = new CsvWriter(CsvWriter.metricsPath("AcquisitionTail", "tail-p999-error.csv"), wideHeader);
             CsvWriter max = new CsvWriter(CsvWriter.metricsPath("AcquisitionTail", "tail-max-ratio.csv"), wideHeader);
             CsvWriter timeouts = new CsvWriter(CsvWriter.metricsPath("AcquisitionTail", "tail-timeouts.csv"),
                     "trace", "recorder", "true_timeouts", "true_max_ns", "reported_max_ns", "timeout_visible")) {
            for (WorkloadTrace trace : traces) {
                long[] values = trace.values();
                long[] timestamps = trace.timestampsNanos();
                double[] exact = new double[QUANTILES.length];
                for (int i = 0; i < QUANTILES.length; i++) {
                    exact[i] = ExactQuantileBaseline.of(values, values.length, QUANTILES[i]);
                }
                long trueMax = 0;
                int trueTimeouts = 0;
                for (long v : values) {
                    trueMax = Math.max(trueMax, v);
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
                    Clock.Manual clock = new Clock.Manual();
                    SamplingStrategy recorder = StrategyCatalog.create(
                            name, StrategyCatalog.DEFAULT_K, clock, StrategyCatalog.DEFAULT_SEED);
                    for (int i = 0; i < values.length; i++) {
                        clock.set(timestamps[i]);
                        recorder.update(values[i], timestamps[i]);
                    }
                    for (int i = 0; i < QUANTILES.length; i++) {
                        double estimate = recorder.valueAtQuantile(QUANTILES[i]);
                        double error = ExactQuantileBaseline.relativeError(estimate, exact[i]);
                        longForm.writeRow(trace.name(), values.length, name, QUANTILES[i], exact[i], estimate, error);
                        if (QUANTILES[i] == 0.999) {
                            p999Row[r + 1] = error;
                        }
                    }
                    double reportedMax = recorder instanceof MicrometerSummary micrometer
                            ? micrometer.max() : recorder.valueAtQuantile(1.0);
                    longForm.writeRow(trace.name(), values.length, name, 1.0, trueMax, reportedMax,
                            ExactQuantileBaseline.relativeError(reportedMax, trueMax));
                    maxRow[r + 1] = reportedMax / trueMax;
                    timeouts.writeRow(trace.name(), name, trueTimeouts, trueMax, reportedMax,
                            trueTimeouts > 0 && reportedMax >= 0.9 * TIMEOUT_NANOS);
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
