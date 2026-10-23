package com.vladmihalcea.phd.reservoir.catalog;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.framework.DropwizardExpDecay;
import com.vladmihalcea.phd.reservoir.framework.DropwizardLockFreeExpDecay;
import com.vladmihalcea.phd.reservoir.framework.DropwizardSlidingTimeWindow;
import com.vladmihalcea.phd.reservoir.framework.DropwizardUniform;
import com.vladmihalcea.phd.reservoir.framework.HdrHistogramRecorderStrategy;
import com.vladmihalcea.phd.reservoir.framework.HdrHistogramStrategy;
import com.vladmihalcea.phd.reservoir.framework.MicrometerSummary;
import com.vladmihalcea.phd.reservoir.framework.OpenTelemetryHistogram;
import com.vladmihalcea.phd.reservoir.framework.PrometheusNativeHistogram;
import com.vladmihalcea.phd.reservoir.framework.PrometheusSummaryCKMS;
import com.vladmihalcea.phd.reservoir.impl.EfraimidisSpirakisARes;
import com.vladmihalcea.phd.reservoir.impl.EfraimidisSpirakisAExpJ;
import com.vladmihalcea.phd.reservoir.impl.ForwardDecayReservoir;
import com.vladmihalcea.phd.reservoir.impl.PrometheusExponentialHistogram;
import com.vladmihalcea.phd.reservoir.impl.SkipReservoirL;
import com.vladmihalcea.phd.reservoir.impl.VitterR;
import com.vladmihalcea.phd.reservoir.impl.WrsBBatched;
import com.vladmihalcea.phd.reservoir.vm.VictoriaMetricsSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Central construction point for every strategy under study, so the benchmarks and the accuracy
 * experiments exercise an identical, consistently parameterized set. Sizes follow the production
 * defaults (Dropwizard k=1028, VictoriaMetrics k=1000) so the comparison reflects real deployments.
 *
 * @author Vlad Mihalcea
 */
public final class StrategyCatalog {

    /** Quantiles reported throughout the study; SLO-relevant tail quantiles included. */
    public static final double[] TARGET_QUANTILES = {0.5, 0.9, 0.95, 0.99, 0.999};

    public static final int DEFAULT_K = 1028;
    public static final long DEFAULT_SEED = 42L;
    public static final long TRACE_SPAN_NANOS = TimeUnit.MINUTES.toNanos(10);
    public static final long HDR_MAX_NANOS = TimeUnit.SECONDS.toNanos(60);
    public static final int HDR_SIG_DIGITS = 3;
    public static final long PROM_MIN_NANOS = 100_000L;      // 0.1 ms
    public static final int PROM_BUCKETS = 160;

    /**
     * The seed of a strategy's own random numbers in an experiment whose stream is generated from {@code streamSeed}.
     * The workloads and the seeded strategies both draw from {@code SplittableRandom}, so the same seed hands the
     * strategy the very numbers that generated the values: with weight 1, A-Res keys each item on the uniform that
     * produced it and keeps the k largest values (a Pareto p50 98 times too high), and A-ExpJ and WRS-B are biased
     * the same way. The constant differs from the one {@code VictoriaMetricsSummary} XORs into its second
     * reservoir's seed, so that reservoir does not meet the stream's seed either.
     */
    public static long strategySeed(long streamSeed) {
        return new SplittableRandom(streamSeed ^ 0xD1B54A32D192ED03L).nextLong();
    }

    private StrategyCatalog() {
    }

    /**
     * One factory per named strategy. Factories rebuild a fresh instance so each experiment repetition
     * (seeded per run) starts clean. The {@code clock} drives all time-aware strategies.
     */
    public static List<NamedStrategy> all(int k, Clock clock, long seed) {
        List<NamedStrategy> list = new ArrayList<>();
        // Reference implementations (the "theory" side).
        list.add(of("Vitter-R", () -> new VitterR(k, seed)));
        list.add(of("Vitter-L-skip", () -> new SkipReservoirL(k, seed)));
        list.add(of("ES-A-Res", () -> new EfraimidisSpirakisARes(k, seed)));
        list.add(of("ES-A-ExpJ", () -> new EfraimidisSpirakisAExpJ(k, seed)));
        list.add(of("ForwardDecay-0.015", () -> new ForwardDecayReservoir(k, 0.015, clock, seed)));
        list.add(of("WRS-B", () -> new WrsBBatched(k, 1024, seed)));
        // Production frameworks (the "practice" side).
        list.add(of("Dropwizard-Uniform", () -> new DropwizardUniform(k)));
        list.add(of("Dropwizard-ExpDecay", () -> new DropwizardExpDecay(k, 0.015, clock)));
        list.add(of("Dropwizard-SlidingWindow", () -> new DropwizardSlidingTimeWindow(5, TimeUnit.MINUTES, clock)));
        list.add(of("Micrometer-Summary", () -> new MicrometerSummary(TARGET_QUANTILES)));
        list.add(of("Prometheus-CKMS", () -> new PrometheusSummaryCKMS(TARGET_QUANTILES)));
        list.add(of("Prometheus-Native", PrometheusNativeHistogram::new));
        list.add(of("Prometheus-Histogram", () -> new PrometheusExponentialHistogram(PROM_MIN_NANOS, HDR_MAX_NANOS, PROM_BUCKETS)));
        list.add(of("OpenTelemetry-Explicit", () -> new OpenTelemetryHistogram(OpenTelemetryHistogram.Kind.EXPLICIT)));
        list.add(of("OpenTelemetry-Exponential", () -> new OpenTelemetryHistogram(OpenTelemetryHistogram.Kind.EXPONENTIAL)));
        list.add(of("HdrHistogram", () -> new HdrHistogramStrategy(HDR_MAX_NANOS, HDR_SIG_DIGITS)));
        list.add(of("VictoriaMetrics", () -> new VictoriaMetricsSummary(clock, seed)));
        return list;
    }

    public static List<NamedStrategy> all(Clock clock, long seed) {
        return all(DEFAULT_K, clock, seed);
    }

    /**
     * Builds a single strategy by its catalog name at an explicit reservoir size, used by the JMH
     * benchmarks that sweep {@code k} via {@code @Param}. Histogram strategies ignore {@code k}.
     */
    public static SamplingStrategy create(String name, int k, Clock clock, long seed) {
        return switch (name) {
            case "Vitter-R" -> new VitterR(k, seed);
            case "Vitter-L-skip" -> new SkipReservoirL(k, seed);
            case "ES-A-Res" -> new EfraimidisSpirakisARes(k, seed);
            case "ES-A-ExpJ" -> new EfraimidisSpirakisAExpJ(k, seed);
            case "ForwardDecay-0.015" -> new ForwardDecayReservoir(k, 0.015, clock, seed);
            case "WRS-B" -> new WrsBBatched(k, 1024, seed);
            case "Dropwizard-Uniform" -> new DropwizardUniform(k);
            case "Dropwizard-ExpDecay" -> new DropwizardExpDecay(k, 0.015, clock);
            case "Dropwizard-LockFreeExpDecay" -> new DropwizardLockFreeExpDecay(k, 0.015, clock);
            case "Dropwizard-SlidingWindow" -> new DropwizardSlidingTimeWindow(5, TimeUnit.MINUTES, clock);
            case "Micrometer-Summary" -> new MicrometerSummary(TARGET_QUANTILES);
            case "Prometheus-CKMS" -> new PrometheusSummaryCKMS(TARGET_QUANTILES);
            case "Prometheus-Native" -> new PrometheusNativeHistogram();
            case "Prometheus-Histogram" -> new PrometheusExponentialHistogram(PROM_MIN_NANOS, HDR_MAX_NANOS, PROM_BUCKETS);
            case "OpenTelemetry-Explicit" -> new OpenTelemetryHistogram(OpenTelemetryHistogram.Kind.EXPLICIT);
            case "OpenTelemetry-Exponential" -> new OpenTelemetryHistogram(OpenTelemetryHistogram.Kind.EXPONENTIAL);
            case "HdrHistogram" -> new HdrHistogramStrategy(HDR_MAX_NANOS, HDR_SIG_DIGITS);
            case "HdrHistogram-Recorder" -> new HdrHistogramRecorderStrategy(HDR_MAX_NANOS, HDR_SIG_DIGITS);
            case "VictoriaMetrics" -> new VictoriaMetricsSummary(clock, seed);
            default -> throw new IllegalArgumentException("Unknown strategy: " + name);
        };
    }

    /**
     * Like {@link #create}, but every time-aware recorder follows {@code clock}, including Micrometer, whose
     * percentile window {@link #create} leaves on the wall clock (the survey's experiments replay a stream
     * in a fraction of a second of wall time, so there its window never rotates). The time-based recency and
     * forward-decay experiments of the journal extension build their recorders here, so Micrometer forgets
     * on the same logical time as Dropwizard, the sliding window, and VictoriaMetrics.
     */
    public static SamplingStrategy createOnClock(String name, int k, Clock clock, long seed) {
        if ("Micrometer-Summary".equals(name)) {
            return new MicrometerSummary(TARGET_QUANTILES, clock);
        }
        return create(name, k, clock, seed);
    }

    /**
     * The recorders of the time-based recency experiment: the production recorders, with the lock-free
     * Dropwizard reservoir and the VictoriaMetrics summary, and the cumulative HdrHistogram, which never
     * forgets, as the reference that shows what no recency looks like. The OpenTelemetry histograms are cumulative
     * too (the OTLP default temporality), so their recency is that of the backend's rate window, outside the SDK.
     */
    public static final List<String> RECENCY_RECORDERS = List.of(
            "Dropwizard-ExpDecay", "Dropwizard-LockFreeExpDecay", "Dropwizard-Uniform",
            "Dropwizard-SlidingWindow", "Micrometer-Summary", "VictoriaMetrics", "HdrHistogram",
            "OpenTelemetry-Explicit", "OpenTelemetry-Exponential");

    /**
     * Strategies whose {@code update} is safe to call concurrently without an external lock, because the
     * underlying production structure synchronizes internally (Dropwizard reservoirs, Micrometer) or is
     * lock-free/atomic (the Prometheus client metrics). The remaining strategies are single-writer
     * reservoirs that a real deployment must guard with a lock — which is exactly the contention pattern
     * the concurrency experiment (E2) measures.
     */
    private static final java.util.Set<String> THREAD_SAFE = Set.of(
            "Dropwizard-Uniform", "Dropwizard-ExpDecay", "Dropwizard-LockFreeExpDecay", "Dropwizard-SlidingWindow",
            "Micrometer-Summary", "Prometheus-CKMS", "Prometheus-Native", "HdrHistogram-Recorder",
            "OpenTelemetry-Explicit", "OpenTelemetry-Exponential");

    /**
     * The production recorders whose sampling draws from an unseeded generator (Dropwizard's reservoirs use
     * {@code ThreadLocalRandom}), so two replays of the same stream retain different samples. An experiment that wants
     * the probability of an outcome for them, such as a timeout showing in the reported maximum, replays them many
     * times; every other recorder of the catalog is deterministic for a given stream and seed.
     */
    public static final Set<String> UNSEEDED = Set.of(
            "Dropwizard-ExpDecay", "Dropwizard-LockFreeExpDecay", "Dropwizard-Uniform");

    /**
     * The largest value a recorder shows: Micrometer's own decaying maximum (what a dashboard plots as the max; its
     * {@code valueAtQuantile(1.0)} would be the nearest published percentile), the highest quantile the Prometheus
     * summary tracks (p99.9, since a summary keeps no maximum, so a timeout shows there only if more than 0.1% of the
     * values time out), and the largest retained sample or the histogram's maximum for every other recorder.
     */
    public static double reportedTop(SamplingStrategy strategy) {
        if (strategy instanceof MicrometerSummary micrometer) {
            return micrometer.max();
        }
        return strategy.valueAtQuantile(1.0);
    }

    /**
     * The recorders an application actually wires behind a latency timer, in the order the
     * acquisition-tail study (virtual-threads follow-up) reports them: FlexyPool's default Dropwizard
     * {@code ExponentiallyDecayingReservoir}, its lock-free sibling, the uniform and sliding-window
     * reservoirs, Micrometer's summary, the Prometheus client's summary and native histogram, HdrHistogram, and the
     * OpenTelemetry SDK's explicit-bucket and exponential histograms. The lock-free reservoir is reachable only through
     * {@link #create} and this list, so the strategy set of the survey's experiments ({@link #all}) is
     * unchanged.
     */
    public static final List<String> PRODUCTION_RECORDERS = List.of(
            "Dropwizard-ExpDecay", "Dropwizard-LockFreeExpDecay", "Dropwizard-Uniform",
            "Dropwizard-SlidingWindow", "Micrometer-Summary", "Prometheus-CKMS", "Prometheus-Native", "HdrHistogram",
            "OpenTelemetry-Explicit", "OpenTelemetry-Exponential");

    /**
     * The same recorders in the form an application shares between threads, for the concurrency
     * experiments: HdrHistogram appears as its wait-free {@code Recorder}, since a plain {@code Histogram} is
     * single-writer.
     */
    public static final List<String> CONCURRENT_RECORDERS = List.of(
            "Dropwizard-ExpDecay", "Dropwizard-LockFreeExpDecay", "Dropwizard-Uniform",
            "Dropwizard-SlidingWindow", "Micrometer-Summary", "Prometheus-CKMS", "Prometheus-Native",
            "HdrHistogram-Recorder", "OpenTelemetry-Explicit", "OpenTelemetry-Exponential");

    /** Whether {@link #create}'d strategy {@code name} tolerates concurrent {@code update} without a lock. */
    public static boolean isThreadSafe(String name) {
        return THREAD_SAFE.contains(name);
    }

    private static NamedStrategy of(String name, Supplier<SamplingStrategy> factory) {
        return new NamedStrategy(name, factory);
    }

    /**
     * A stable display name bound to a factory that produces fresh strategy instances on demand.
     */
    public record NamedStrategy(String name, Supplier<SamplingStrategy> factory) {
        public SamplingStrategy create() {
            return factory.get();
        }
    }
}
