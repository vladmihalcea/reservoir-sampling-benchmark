package com.vladmihalcea.phd.reservoir.framework;

import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/**
 * Adapter over HdrHistogram's {@code Recorder}, the form an application shares between threads: writers
 * record wait-free into an active histogram behind a writer-reader phaser, and a reader swaps it for an
 * inactive one to take an interval snapshot. Unlike {@link HdrHistogramStrategy}, which wraps a plain
 * single-writer {@code Histogram}, it tolerates concurrent {@code update} calls without an external lock.
 * Queries accumulate every interval taken so far, so {@link #valueAtQuantile} covers the whole stream.
 *
 * @author Vlad Mihalcea
 */
public class HdrHistogramRecorderStrategy implements SamplingStrategy {

    private final long highestTrackableValue;
    private final int significantDigits;
    private Recorder recorder;
    private Histogram total;
    private Histogram interval;

    public HdrHistogramRecorderStrategy(long highestTrackableValue, int significantDigits) {
        this.highestTrackableValue = highestTrackableValue;
        this.significantDigits = significantDigits;
        reset();
    }

    @Override
    public void update(long value) {
        recorder.recordValue(Math.max(1, Math.min(value, highestTrackableValue)));
    }

    @Override
    public synchronized double valueAtQuantile(double q) {
        interval = recorder.getIntervalHistogram(interval);
        total.add(interval);
        return total.getValueAtPercentile(q * 100.0);
    }

    @Override
    public synchronized void reset() {
        recorder = new Recorder(1, highestTrackableValue, significantDigits);
        total = new Histogram(1, highestTrackableValue, significantDigits);
        interval = null;
    }

    @Override
    public String id() {
        return "HdrHistogram-Recorder(sig=" + significantDigits + ")";
    }

    @Override
    public Taxonomy taxonomy() {
        return Taxonomy.NON_RS;
    }
}
