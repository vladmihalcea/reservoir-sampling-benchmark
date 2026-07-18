package com.vladmihalcea.phd.reservoir.api;

/**
 * Single Service Provider Interface implemented by every quantile-estimation strategy
 * under study, whether it originates from a production metrics framework (Dropwizard,
 * Micrometer, VictoriaMetrics, ...) or from the academic literature (Vitter, Efraimidis-Spirakis,
 * Hübschle-Schneider, ...).
 * <p>
 * The stream carries {@code long} observations (nanosecond latencies in the realistic traces),
 * matching how APM timers record values.
 *
 * @author Vlad Mihalcea
 */
public interface SamplingStrategy {

    /**
     * Records a single observation using the strategy's own (wall-clock) time source, if any.
     */
    void update(long value);

    /**
     * Records a single observation stamped with an explicit logical time in nanoseconds.
     * <p>
     * Time-decayed and windowed strategies (forward decay, Dropwizard's
     * {@code ExponentiallyDecayingReservoir}, sliding window, VictoriaMetrics swap) honor the
     * supplied timestamp so that accuracy experiments can drive them from a deterministic clock.
     * Time-agnostic reservoirs simply ignore it.
     */
    default void update(long value, long timeNanos) {
        update(value);
    }

    /**
     * Estimates the value at the given quantile ({@code 0.0 <= q <= 1.0}) from the current sample.
     */
    double valueAtQuantile(double q);

    /**
     * Clears all recorded state so the instance can be reused across benchmark iterations.
     */
    void reset();

    /**
     * Stable short identifier used in CSV rows, chart legends and log files.
     */
    String id();

    /**
     * Which class of the Hübschle-Schneider weighted-reservoir taxonomy this strategy belongs to.
     * Frameworks that do not perform reservoir sampling at all report {@link Taxonomy#NON_RS}.
     */
    Taxonomy taxonomy();
}
