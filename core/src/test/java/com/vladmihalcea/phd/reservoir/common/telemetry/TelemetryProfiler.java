package com.vladmihalcea.phd.reservoir.common.telemetry;

import com.vladmihalcea.phd.reservoir.common.telemetry.TelemetrySampler.Metric;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.profile.ProfilerException;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ScalarResult;
import org.openjdk.jmh.runner.IterationType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * JMH profiler that reports the CPU telemetry of every iteration as secondary results,
 * prefixed by {@code app.} for the application host, which runs the benchmark,
 * and by {@code db.} for the database host, e.g., {@code app.cpu.mhz} and {@code db.cpu.mhz}.
 * <p>
 * The database host metrics require a {@link TelemetryAgent} running on the database host,
 * whose URL is given by the {@code agent} profiler option or by the {@code TELEMETRY_AGENT_URL}
 * environment variable, e.g.:
 * <pre>
 * -prof com.vladmihalcea.phd.reservoir.common.telemetry.TelemetryProfiler:agent=http://192.168.1.20:9500
 * </pre>
 * The profiler reads the agent before and after every iteration, so the network calls are not measured.
 *
 * @author Vlad Mihalcea
 */
public class TelemetryProfiler implements InternalProfiler {

    private static final String AGENT_OPTION = "agent=";

    private static final Duration AGENT_TIMEOUT = Duration.ofSeconds(5);

    private static TelemetrySampler sampler;

    private final URI agentUri;

    private final HttpClient httpClient;

    private long applicationHostFrom;

    private long databaseHostFrom;

    private int measurementIterations;

    public TelemetryProfiler(String options) throws ProfilerException {
        String url = options != null && options.startsWith(AGENT_OPTION) ?
            options.substring(AGENT_OPTION.length()) :
            System.getenv("TELEMETRY_AGENT_URL");
        if (url == null || url.isBlank()) {
            agentUri = null;
            httpClient = null;
        } else {
            agentUri = URI.create(url.endsWith("/") ? url : url + "/");
            httpClient = HttpClient.newBuilder().connectTimeout(AGENT_TIMEOUT).build();
            try {
                get("now");
            } catch (IOException | RuntimeException e) {
                throw new ProfilerException("Cannot connect to the telemetry agent: " + agentUri, e);
            }
        }
    }

    @Override
    public String getDescription() {
        return "CPU frequency, utilization, performance limit, temperature, and thermal throttling of the application and database hosts";
    }

    @Override
    public void beforeIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams) {
        if (sampler == null) {
            sampler = TelemetrySampler.start(maxDuration(benchmarkParams));
        }
        applicationHostFrom = sampler.now();
        if (agentUri != null) {
            try {
                databaseHostFrom = Long.parseLong(get("now"));
            } catch (IOException | RuntimeException e) {
                databaseHostFrom = -1;
                System.err.println("Cannot read the telemetry agent: " + e);
            }
        }
    }

    @Override
    public Collection<? extends Result> afterIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams, IterationResult result) {
        List<Result> results = new ArrayList<>();
        addResults(results, "app.", sampler.summary(applicationHostFrom));
        if (agentUri != null && databaseHostFrom >= 0) {
            try {
                addResults(results, "db.", TelemetryAgent.parse(get("summary?from=" + databaseHostFrom)));
            } catch (IOException | RuntimeException e) {
                System.err.println("Cannot read the telemetry agent: " + e);
            }
        }
        if (iterationParams.getType() == IterationType.MEASUREMENT && ++measurementIterations == iterationParams.getCount()) {
            sampler.close();
            sampler = null;
        }
        return results;
    }

    private static void addResults(List<Result> results, String prefix, Map<Metric, Double> summary) {
        summary.forEach((metric, value) -> results.add(
            new ScalarResult(prefix + metric.label(), value, metric.unit(), AggregationPolicy.valueOf(metric.aggregation().name()))
        ));
    }

    private String get(String path) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(agentUri.resolve(path))
            .timeout(AGENT_TIMEOUT)
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Unexpected status " + response.statusCode() + " for " + request.uri());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    /**
     * @return an upper bound of the fork duration, after which the sampler stops even if the fork gets killed
     */
    private static Duration maxDuration(BenchmarkParams benchmarkParams) {
        return Duration.ofHours(1)
            .plus(duration(benchmarkParams.getWarmup()))
            .plus(duration(benchmarkParams.getMeasurement()));
    }

    private static Duration duration(IterationParams iterationParams) {
        return Duration.ofMillis(iterationParams.getTime().convertTo(TimeUnit.MILLISECONDS) * iterationParams.getCount());
    }
}
