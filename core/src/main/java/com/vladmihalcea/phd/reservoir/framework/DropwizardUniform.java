package com.vladmihalcea.phd.reservoir.framework;

import com.codahale.metrics.Snapshot;
import com.codahale.metrics.UniformReservoir;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;

/**
 * Adapter over Dropwizard Metrics' {@code UniformReservoir}, which implements Vitter's Algorithm R
 * with a default size of 1028. This is the "practice" counterpart of the reference {@code VitterR}
 * strategy and lets the survey confirm that Dropwizard's production code matches the textbook
 * algorithm (and shares its {@code O(n)} random-draw cost).
 *
 * @author Vlad Mihalcea
 */
public class DropwizardUniform implements SamplingStrategy {

    public static final int DEFAULT_SIZE = 1028;

    private final int size;
    private UniformReservoir reservoir;

    public DropwizardUniform() {
        this(DEFAULT_SIZE);
    }

    public DropwizardUniform(int size) {
        this.size = size;
        this.reservoir = new UniformReservoir(size);
    }

    @Override
    public void update(long value) {
        reservoir.update(value);
    }

    @Override
    public double valueAtQuantile(double q) {
        Snapshot snapshot = reservoir.getSnapshot();
        return snapshot.getValue(q);
    }

    @Override
    public void reset() {
        reservoir = new UniformReservoir(size);
    }

    @Override
    public String id() {
        return "Dropwizard-Uniform(k=" + size + ")";
    }

    @Override
    public Taxonomy taxonomy() {
        // Delegates to Vitter Algorithm R (uniform): the equal-weight case of without-replacement
        // sampling, WRS-N rather than WRS-R.
        return Taxonomy.WRS_N;
    }
}
