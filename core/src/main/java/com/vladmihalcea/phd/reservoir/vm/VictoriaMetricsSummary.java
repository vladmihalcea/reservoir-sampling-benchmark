package com.vladmihalcea.phd.reservoir.vm;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;
import com.vladmihalcea.phd.reservoir.impl.VitterR;

import java.util.concurrent.TimeUnit;

/**
 * Faithful Java port of the strategy VictoriaMetrics' {@code Summary} delegates to
 * (valyala/histogram): two uniform (Vitter Algorithm R) reservoirs of 1000 samples driven over a
 * five-minute window. New observations fill the "next" reservoir; at the middle of the window the two
 * are swapped — "next" is promoted to "current" and a fresh "next" is started — so a quantile query
 * always reads a reservoir holding roughly the last half-window of data.
 * <p>
 * The port is deterministic (seeded reservoirs, injectable {@link Clock}) so its window behavior can be
 * unit-tested against the documented Go semantics and replayed in the recency experiment (E8). It backs
 * the survey's claim that VictoriaMetrics does still sample (unlike its {@code vmrange} histogram path),
 * using the oldest algorithm in the taxonomy.
 *
 * @author Vlad Mihalcea
 */
public class VictoriaMetricsSummary implements SamplingStrategy {

    public static final int DEFAULT_SAMPLES = 1000;

    private final int samples;
    private final long halfWindowNanos;
    private final Clock clock;
    private final long seed;

    private VitterR current;
    private VitterR next;
    private long nextSwapNanos;

    public VictoriaMetricsSummary(Clock clock, long seed) {
        this(DEFAULT_SAMPLES, TimeUnit.MINUTES.toNanos(5), clock, seed);
    }

    public VictoriaMetricsSummary(int samples, long windowNanos, Clock clock, long seed) {
        this.samples = samples;
        this.halfWindowNanos = windowNanos / 2;
        this.clock = clock;
        this.seed = seed;
        this.current = new VitterR(samples, seed);
        this.next = new VitterR(samples, seed ^ 0x9E3779B97F4A7C15L);
        this.nextSwapNanos = clock.nanoTime() + halfWindowNanos;
    }

    @Override
    public void update(long value) {
        update(value, clock.nanoTime());
    }

    @Override
    public void update(long value, long timeNanos) {
        swapIfNeeded(timeNanos);
        next.update(value);
    }

    private void swapIfNeeded(long timeNanos) {
        while (timeNanos >= nextSwapNanos) {
            current = next;
            next = new VitterR(samples, seed ^ (timeNanos * 0x9E3779B97F4A7C15L));
            nextSwapNanos += halfWindowNanos;
        }
    }

    @Override
    public double valueAtQuantile(double q) {
        double value = current.valueAtQuantile(q);
        if (Double.isNaN(value)) {
            // Before the first swap "current" is empty; fall back to the reservoir being filled.
            return next.valueAtQuantile(q);
        }
        return value;
    }

    @Override
    public void reset() {
        current = new VitterR(samples, seed);
        next = new VitterR(samples, seed ^ 0x9E3779B97F4A7C15L);
        nextSwapNanos = clock.nanoTime() + halfWindowNanos;
    }

    @Override
    public String id() {
        return "VictoriaMetrics-Summary(k=" + samples + ")";
    }

    @Override
    public Taxonomy taxonomy() {
        // Built on Vitter Algorithm R reservoirs (uniform): the equal-weight case of
        // without-replacement sampling, WRS-N rather than WRS-R.
        return Taxonomy.WRS_N;
    }
}
