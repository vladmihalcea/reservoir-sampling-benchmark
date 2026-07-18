package com.vladmihalcea.phd.reservoir.api;

/**
 * The weighted reservoir sampling taxonomy proposed by Hübschle-Schneider and Sanders
 * (ACM TOMS 2022), extended with a {@link #NON_RS} bucket for production strategies that
 * abandon reservoir sampling in favor of fixed-bucket histograms or rank summaries.
 *
 * @author Vlad Mihalcea
 */
public enum Taxonomy {

    /** Single weighted sample (k = 1). */
    WRS_1("WRS-1", "Single sample"),

    /** Weighted sampling with replacement. */
    WRS_R("WRS-R", "Sampling with replacement"),

    /**
     * Sampling without replacement (Efraimidis-Spirakis A-Res / A-ExpJ). Also covers the uniform
     * reservoirs (Vitter Algorithm R / Algorithm L, and the Dropwizard and VictoriaMetrics
     * reservoirs built on them) as the equal-weight (unweighted) case, which Hübschle-Schneider and
     * Sanders treat as a specialization of this class, not as with-replacement (WRS-R) sampling.
     */
    WRS_N("WRS-N", "Sampling without replacement"),

    /** Batched reservoir sampling (Hübschle-Schneider WRS-B). */
    WRS_B("WRS-B", "Batched reservoir sampling"),

    /** Not reservoir sampling: fixed-bucket histogram or biased-quantile rank summary. */
    NON_RS("non-RS", "Histogram / rank summary");

    private final String code;
    private final String description;

    Taxonomy(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }
}
