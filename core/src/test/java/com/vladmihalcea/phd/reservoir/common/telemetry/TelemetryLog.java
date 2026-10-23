package com.vladmihalcea.phd.reservoir.common.telemetry;

import com.vladmihalcea.phd.reservoir.common.telemetry.TelemetrySampler.Metric;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Writes the CPU telemetry of a whole campaign block as a time series, for the blocks that do not run under JMH
 * (the live concurrency, accuracy, and starvation experiments), where the {@link TelemetryProfiler} of the JMH
 * blocks cannot attach. Every {@code interval} seconds it appends one row: the UTC time, and every metric of
 * {@link TelemetrySampler} aggregated over the interval, for the host it runs on ({@code app.}) and, when the URL
 * of a {@link TelemetryAgent} is given, for the database host ({@code db.}). The campaign script starts it next to
 * {@code pidstat} and stops it at the end of the block; a test's own start and end times then place its run in the
 * series.
 * <p>
 * Usage: {@code TelemetryLog <output.csv> [interval seconds, default 10] [agent URL, default $TELEMETRY_AGENT_URL]}
 *
 * @author Vlad Mihalcea
 */
public final class TelemetryLog {

    private static final Duration AGENT_TIMEOUT = Duration.ofSeconds(5);

    private TelemetryLog() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: TelemetryLog <output.csv> [interval seconds] [agent URL]");
            System.exit(2);
        }
        Path output = Path.of(args[0]);
        long intervalMillis = (args.length > 1 ? Long.parseLong(args[1]) : 10) * 1000;
        String url = args.length > 2 ? args[2] : System.getenv("TELEMETRY_AGENT_URL");
        URI agent = url == null || url.isBlank() ? null : URI.create(url.endsWith("/") ? url : url + "/");
        HttpClient http = agent == null ? null : HttpClient.newBuilder().connectTimeout(AGENT_TIMEOUT).build();

        TelemetrySampler sampler = TelemetrySampler.start(null);
        Runtime.getRuntime().addShutdownHook(new Thread(sampler::close));
        Files.createDirectories(output.toAbsolutePath().getParent());
        boolean header = !Files.exists(output);
        try (Writer out = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            if (header) {
                StringBuilder line = new StringBuilder("time_utc");
                for (Metric metric : Metric.values()) {
                    line.append(",app.").append(metric.label());
                }
                if (agent != null) {
                    for (Metric metric : Metric.values()) {
                        line.append(",db.").append(metric.label());
                    }
                }
                out.write(line.append('\n').toString());
                out.flush();
            }
            long appFrom = sampler.now();
            long dbFrom = agent == null ? -1 : agentNow(http, agent);
            while (true) {
                Thread.sleep(intervalMillis);
                StringBuilder line = new StringBuilder(Instant.now().toString());
                appendSummary(line, sampler.summary(appFrom));
                appFrom = sampler.now();
                if (agent != null) {
                    Map<Metric, Double> db = Map.of();
                    if (dbFrom >= 0) {
                        try {
                            db = TelemetryAgent.parse(get(http, agent, "summary?from=" + dbFrom));
                        } catch (IOException | RuntimeException e) {
                            System.err.println("Cannot read the telemetry agent: " + e);
                        }
                    }
                    appendSummary(line, db);
                    dbFrom = agentNow(http, agent);
                }
                out.write(line.append('\n').toString());
                out.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void appendSummary(StringBuilder line, Map<Metric, Double> summary) {
        for (Metric metric : Metric.values()) {
            Double value = summary.get(metric);
            line.append(',');
            if (value != null && !value.isNaN()) {
                line.append(String.format(Locale.ROOT, "%.3f", value));
            }
        }
    }

    private static long agentNow(HttpClient http, URI agent) {
        try {
            return Long.parseLong(get(http, agent, "now").trim());
        } catch (IOException | RuntimeException e) {
            System.err.println("Cannot read the telemetry agent: " + e);
            return -1;
        }
    }

    private static String get(HttpClient http, URI agent, String path) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(agent.resolve(path)).timeout(AGENT_TIMEOUT).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Unexpected status " + response.statusCode() + " for " + request.uri());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
