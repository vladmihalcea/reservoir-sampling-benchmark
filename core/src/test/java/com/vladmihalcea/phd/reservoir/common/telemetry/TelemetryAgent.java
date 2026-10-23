package com.vladmihalcea.phd.reservoir.common.telemetry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vladmihalcea.phd.reservoir.common.telemetry.TelemetrySampler.Metric;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Serves the CPU telemetry of the host it runs on, e.g., the database host, to the {@link TelemetryProfiler}
 * of the benchmarks running on the application host:
 * <ul>
 *     <li>{@code GET /now} returns the time of the agent clock, which the profiler reads when an iteration starts,</li>
 *     <li>{@code GET /summary?from=<time>} returns the metrics aggregated over the samples taken since then.</li>
 * </ul>
 * Since both times come from the agent clock, the two hosts do not need synchronized clocks.
 * <p>
 * Usage: {@code java -cp "test-classes;lib/*" com.vladmihalcea.phd.reservoir.common.telemetry.TelemetryAgent [port]}
 *
 * @author Vlad Mihalcea
 */
public class TelemetryAgent {

    public static final int DEFAULT_PORT = 9500;

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        TelemetrySampler sampler = TelemetrySampler.start(null);
        long start = sampler.now();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/now", exchange -> respond(exchange, String.valueOf(sampler.now())));
        server.createContext("/summary", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            long from = Long.parseLong(query.substring(query.indexOf('=') + 1));
            respond(exchange, format(sampler.summary(from)));
        });
        server.start();

        System.out.printf("Serving the CPU telemetry of %s on port %d%n", InetAddress.getLocalHost().getHostName(), port);
        Thread.sleep(5000);
        System.out.printf("The first samples report:%n%s%n", format(sampler.summary(start)));
    }

    static String format(Map<Metric, Double> summary) {
        return summary.entrySet().stream()
            .map(entry -> entry.getKey().label() + "=" + entry.getValue())
            .collect(Collectors.joining("\n"));
    }

    static Map<Metric, Double> parse(String body) {
        Map<Metric, Double> summary = new EnumMap<>(Metric.class);
        for (String line : body.split("\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                summary.put(Metric.of(line.substring(0, separator)), Double.parseDouble(line.substring(separator + 1)));
            }
        }
        return summary;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
