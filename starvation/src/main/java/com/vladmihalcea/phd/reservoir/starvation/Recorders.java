package com.vladmihalcea.phd.reservoir.starvation;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.framework.MicrometerSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * The live recorders of the starvation study: the core module's thread-safe production recorders
 * ({@link StrategyCatalog#CONCURRENT_RECORDERS}), shared by every client of the {@link StarvationDriver}, plus
 * {@code none} as the baseline that records nothing. Recording through the catalog's adapters means the live
 * experiments and the offline replay of the captured traces score the same code.
 *
 * @author Vlad Mihalcea
 */
public final class Recorders {

    public static final String NONE = "none";

    /** {@code none} first, then the concurrent recorders in reporting order. */
    public static final List<String> IDS;

    static {
        List<String> ids = new ArrayList<>();
        ids.add(NONE);
        ids.addAll(StrategyCatalog.CONCURRENT_RECORDERS);
        IDS = List.copyOf(ids);
    }

    private Recorders() {
    }

    /** A fresh recorder on the system clock, or {@code null} for {@code none}. */
    public static SamplingStrategy create(String id) {
        if (NONE.equals(id)) {
            return null;
        }
        return StrategyCatalog.create(id, StrategyCatalog.DEFAULT_K, Clock.SYSTEM, StrategyCatalog.DEFAULT_SEED);
    }

    /**
     * The maximum the recorder reports: Micrometer's own decaying max, which is what a dashboard shows, or the
     * largest value the recorder retains.
     */
    public static double max(SamplingStrategy recorder) {
        return recorder instanceof MicrometerSummary micrometer ? micrometer.max() : recorder.valueAtQuantile(1.0);
    }
}
