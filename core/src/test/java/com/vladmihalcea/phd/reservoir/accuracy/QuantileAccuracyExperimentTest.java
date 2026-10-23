package com.vladmihalcea.phd.reservoir.accuracy;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
import com.vladmihalcea.phd.reservoir.baseline.TargetTruth;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.util.Statistics;
import com.vladmihalcea.phd.reservoir.workload.SyntheticWorkloads;
import com.vladmihalcea.phd.reservoir.workload.TraceWorkloads;
import com.vladmihalcea.phd.reservoir.workload.Workload;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Experiment E6: quantile accuracy of every strategy against the exact quantile baseline, over every synthetic
 * workload and a range of stream lengths, averaged over repeated seeds with a 95% confidence interval.
 * This is the survey's headline accuracy table (claim C2). Time-aware strategies are driven from a
 * manual clock that advances evenly across the stream (10 minutes) so their decay/window behavior is exercised
 * deterministically.
 * <p>
 * Every estimate is scored twice, and in two ways:
 * <ul>
 *   <li>against the all-time quantiles of the stream, the survey's original score, and against the strategy's own
 *       target ({@link TargetTruth}): the decayed distribution for the forward-decay reservoirs (Dropwizard's
 *       exponentially decaying reservoir and the reference implementation, alpha = 0.015), the last 5 minutes for
 *       Dropwizard's sliding window, and the half-window before the last swap for the VictoriaMetrics summary. The
 *       other strategies summarize the whole stream (Micrometer's window runs on the wall clock here, so it never
 *       rotates during the replay), so both scores coincide for them. On a stationary workload the two targets
 *       barely differ; on the regime shift, the all-time score charges the recency-aware strategies for reporting the
 *       new regime;</li>
 *   <li>by the relative value error {@code |estimate - truth| / truth}, what an operator reads, and by the rank error
 *       under the target's exact CDF, the distance from {@code q} to the ranks the estimate occupies
 *       ({@link TargetTruth#rankError}, {@code |F(estimate) - q|} between values), which is what the sampling
 *       guarantees bound (a reservoir of {@code k} values estimates a rank to about {@code sqrt(q (1 - q) / k)},
 *       however heavy the tail).</li>
 * </ul>
 * <p>
 * One CSV per workload is written under {@code metrics/QuantileAccuracy/}: {@code accuracy-<workload>.csv} with
 * {@code strategy, quantile, stream_length, mean_rel_error, ci95} (all-time, as before) followed by the target and the
 * own-target and rank columns; {@code bars-<workload>.csv} (all-time value error at the longest stream, one column per
 * quantile), {@code bars-own-<workload>.csv} (own-target value error), and {@code ranks-<workload>.csv} (own-target rank
 * error).
 *
 * @author Vlad Mihalcea
 */
public class QuantileAccuracyExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuantileAccuracyExperimentTest.class);

    // Sizes default to publication scale but can be shrunk for a smoke run, e.g. -Drs.reps=2 -Drs.maxStream=10000.
    private static final int MAX_STREAM = Integer.getInteger("rs.maxStream", 1_000_000);
    private static final int[] STREAM_LENGTHS =
            MAX_STREAM >= 1_000_000 ? new int[]{10_000, 1_000_000} : new int[]{Math.min(10_000, MAX_STREAM)};
    private static final int REPETITIONS = Integer.getInteger("rs.reps", 30);
    private static final long BASE_SEED = 1_000L;

    private static final TargetTruth.Target ALL_TIME = new TargetTruth.AllTime();

    /**
     * The distribution each strategy of this experiment summarizes at the end of the stream, given how
     * {@link StrategyCatalog#create} builds it on the experiment's manual clock.
     */
    static TargetTruth.Target ownTarget(String strategy) {
        return switch (strategy) {
            case "ForwardDecay-0.015", "Dropwizard-ExpDecay", "Dropwizard-LockFreeExpDecay" ->
                    new TargetTruth.Decayed(0.015);
            case "Dropwizard-SlidingWindow" -> new TargetTruth.Window(TimeUnit.MINUTES.toNanos(5));
            case "VictoriaMetrics" -> new TargetTruth.LastHalfWindow(TimeUnit.MINUTES.toNanos(5) / 2);
            default -> ALL_TIME;
        };
    }

    /** The error statistics of one strategy over the repetitions, per quantile. */
    private static final class Scores {
        final Statistics[] allTime;
        final Statistics[] own;
        final Statistics[] rank;
        final Statistics[] ownRank;

        Scores(int quantiles) {
            allTime = new Statistics[quantiles];
            own = new Statistics[quantiles];
            rank = new Statistics[quantiles];
            ownRank = new Statistics[quantiles];
            for (int i = 0; i < quantiles; i++) {
                allTime[i] = new Statistics();
                own[i] = new Statistics();
                rank[i] = new Statistics();
                ownRank[i] = new Statistics();
            }
        }
    }

    @Test
    public void measureAccuracy() {
        int maxLength = STREAM_LENGTHS[STREAM_LENGTHS.length - 1];
        // Synthetic distributions plus the captured "realistic" traces (claim C2: synthetic AND realistic).
        List<Workload> workloads = new ArrayList<>(SyntheticWorkloads.all());
        if (TraceWorkloads.available()) {
            workloads.addAll(TraceWorkloads.all());
        } else {
            LOGGER.warn("Trace resources not on classpath; running synthetic workloads only");
        }
        String[] wideHeader = {"strategy", "p50", "p90", "p95", "p99", "p999"};
        for (Workload workload : workloads) {
            Path csv = CsvWriter.metricsPath("QuantileAccuracy", "accuracy-" + workload.name() + ".csv");
            // Chart-ready wide form (one row per strategy, one column per quantile) at the longest stream.
            try (CsvWriter out = new CsvWriter(csv,
                    "strategy", "quantile", "stream_length", "mean_rel_error", "ci95", "target",
                    "own_rel_error", "own_ci95", "rank_error", "rank_ci95", "own_rank_error", "own_rank_ci95");
                 CsvWriter wide = new CsvWriter(
                         CsvWriter.metricsPath("QuantileAccuracy", "bars-" + workload.name() + ".csv"), wideHeader);
                 CsvWriter wideOwn = new CsvWriter(
                         CsvWriter.metricsPath("QuantileAccuracy", "bars-own-" + workload.name() + ".csv"), wideHeader);
                 CsvWriter wideRank = new CsvWriter(
                         CsvWriter.metricsPath("QuantileAccuracy", "ranks-" + workload.name() + ".csv"), wideHeader)) {
                // A captured trace holds a fixed number of values (50,000), so its stream is at most that long: a
                // longer request returns the whole trace, and its row carries the trace's own length. The rerun of
                // 17 Nov sized the timestamps by the request and ran past the trace's end.
                int[] lengths = Arrays.stream(STREAM_LENGTHS)
                        .map(length -> workload.generate(length, BASE_SEED).length).distinct().toArray();
                int longestLength = lengths[lengths.length - 1];
                if (longestLength < maxLength) {
                    LOGGER.info("Workload '{}' holds {} values: its longest stream is {}, not {}", workload.name(),
                            longestLength, longestLength, maxLength);
                }
                for (int streamLength : lengths) {
                    boolean longest = streamLength == longestLength;
                    runWorkload(workload, streamLength, out, longest ? wide : null, longest ? wideOwn : null,
                            longest ? wideRank : null);
                }
            }
            LOGGER.info("Wrote accuracy CSVs for workload '{}' to {}", workload.name(), csv);
        }
    }

    private void runWorkload(Workload workload, int streamLength, CsvWriter out, CsvWriter wide, CsvWriter wideOwn,
                             CsvWriter wideRank) {
        double[] quantiles = StrategyCatalog.TARGET_QUANTILES;
        List<String> strategyNames = StrategyCatalog.all(Clock.SYSTEM, 1L).stream()
                .map(StrategyCatalog.NamedStrategy::name).toList();
        Map<String, Scores> scores = new LinkedHashMap<>();
        for (String name : strategyNames) {
            scores.put(name, new Scores(quantiles.length));
        }
        long step = Math.max(1, StrategyCatalog.TRACE_SPAN_NANOS / streamLength);

        for (int rep = 0; rep < REPETITIONS; rep++) {
            long seed = BASE_SEED + rep;
            long[] values = workload.generate(streamLength, seed);
            if (values.length != streamLength) {
                throw new IllegalStateException(workload.name() + " returned " + values.length + " values for a stream of "
                        + streamLength);
            }
            long[] timestamps = new long[streamLength];
            for (int i = 0; i < streamLength; i++) {
                timestamps[i] = (i + 1) * step;
            }
            // One exact distribution per target, shared by the strategies of this repetition.
            Map<TargetTruth.Target, TargetTruth> truths = new LinkedHashMap<>();
            truths.put(ALL_TIME, TargetTruth.of(ALL_TIME, values, timestamps));

            for (String strategyName : strategyNames) {
                Clock.Manual clock = new Clock.Manual();
                SamplingStrategy strategy = StrategyCatalog.create(
                        strategyName, StrategyCatalog.DEFAULT_K, clock, StrategyCatalog.strategySeed(seed));
                for (int i = 0; i < streamLength; i++) {
                    clock.advanceNanos(step);
                    strategy.update(values[i], clock.nanoTime());
                }
                TargetTruth allTime = truths.get(ALL_TIME);
                TargetTruth own = truths.computeIfAbsent(ownTarget(strategyName),
                        target -> TargetTruth.of(target, values, timestamps));
                Scores s = scores.get(strategyName);
                for (int i = 0; i < quantiles.length; i++) {
                    double estimate = strategy.valueAtQuantile(quantiles[i]);
                    s.allTime[i].add(ExactQuantileBaseline.relativeError(estimate, allTime.valueAtQuantile(quantiles[i])));
                    s.own[i].add(ExactQuantileBaseline.relativeError(estimate, own.valueAtQuantile(quantiles[i])));
                    s.rank[i].add(allTime.rankError(estimate, quantiles[i]));
                    s.ownRank[i].add(own.rankError(estimate, quantiles[i]));
                }
            }
        }

        for (String strategyName : strategyNames) {
            Scores s = scores.get(strategyName);
            String target = ownTarget(strategyName).label();
            for (int i = 0; i < quantiles.length; i++) {
                out.writeRow(strategyName, quantiles[i], streamLength, s.allTime[i].mean(), s.allTime[i].ci95HalfWidth(),
                        target, s.own[i].mean(), s.own[i].ci95HalfWidth(), s.rank[i].mean(), s.rank[i].ci95HalfWidth(),
                        s.ownRank[i].mean(), s.ownRank[i].ci95HalfWidth());
            }
            if (wide != null) {
                wide.writeRow(strategyName, s.allTime[0].mean(), s.allTime[1].mean(), s.allTime[2].mean(),
                        s.allTime[3].mean(), s.allTime[4].mean());
                wideOwn.writeRow(strategyName, s.own[0].mean(), s.own[1].mean(), s.own[2].mean(),
                        s.own[3].mean(), s.own[4].mean());
                wideRank.writeRow(strategyName, s.ownRank[0].mean(), s.ownRank[1].mean(), s.ownRank[2].mean(),
                        s.ownRank[3].mean(), s.ownRank[4].mean());
            }
        }
    }
}
