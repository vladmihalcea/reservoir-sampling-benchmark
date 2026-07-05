package com.vladmihalcea.phd.reservoir.framework;

import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;
import org.HdrHistogram.Histogram;

/**
 * Adapter over HdrHistogram, the fixed-range/fixed-precision histogram that Micrometer uses internally
 * for its percentile histograms and that HdrHistogram's author positions as the non-sampling
 * alternative to reservoirs. It records every value into a logarithmic bucket with a configured number
 * of significant digits, giving exact-within-precision quantiles and zero steady-state allocation — the
 * memory-vs-accuracy baseline for experiments E4 and E6.
 *
 * @author Vlad Mihalcea
 */
public class HdrHistogramStrategy implements SamplingStrategy {

    private final long highestTrackableValue;
    private final int significantDigits;
    private final Histogram histogram;

    public HdrHistogramStrategy(long highestTrackableValue, int significantDigits) {
        this.highestTrackableValue = highestTrackableValue;
        this.significantDigits = significantDigits;
        this.histogram = new Histogram(1, highestTrackableValue, significantDigits);
    }

    @Override
    public void update(long value) {
        histogram.recordValue(Math.max(1, Math.min(value, highestTrackableValue)));
    }

    @Override
    public double valueAtQuantile(double q) {
        return histogram.getValueAtPercentile(q * 100.0);
    }

    @Override
    public void reset() {
        histogram.reset();
    }

    @Override
    public String id() {
        return "HdrHistogram(sig=" + significantDigits + ")";
    }

    @Override
    public Taxonomy taxonomy() {
        return Taxonomy.NON_RS;
    }
}
