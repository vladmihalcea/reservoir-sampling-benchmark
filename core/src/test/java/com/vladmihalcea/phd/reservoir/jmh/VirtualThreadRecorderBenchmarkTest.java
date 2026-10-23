package com.vladmihalcea.phd.reservoir.jmh;

import com.vladmihalcea.phd.reservoir.common.timing.RecordingProfiler;
import com.vladmihalcea.phd.reservoir.common.util.AbstractBenchmarkTest;
import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.Profiler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs experiment V6 ({@link VirtualThreadRecorderBenchmark}). The full grid is 11 recorders (with the
 * {@code none} baseline) &times; 2 thread kinds &times; 3 client counts &times; 2 I/O times = 132 cells; with the
 * November campaign's 5 &times; 3 s warm-up, 10 &times; 3 s measurement, and 10 forks it took about 17 hours, and with
 * the {@code vt-jmh-long} budget (10 &times; 3 s warm-up, 20 &times; 3 s measurement, 10 forks), which lets the
 * throughput of the 10,000-client cells settle and collects more scrapes per fork, about 37 hours. The
 * campaign's {@code otel} block runs only the OpenTelemetry cells with same-run references. Narrow it for
 * a smoke run with {@code -Djmh.param.clients=200 -Djmh.param.ioMicros=1000}. The side CSV of an earlier run is
 * deleted first, unless {@code -Drs.append=true}.
 * <p>
 * Besides the telemetry of every JMH block, it attaches the {@link RecordingProfiler} (the recorder's update
 * latency, the response time, the scrape time, and the CPU load per iteration, {@code rec.*}) and JMH's GC profiler
 * (the allocation rate, which the sliding window raises with every retained sample).
 *
 * @author Vlad Mihalcea
 */
public class VirtualThreadRecorderBenchmarkTest extends AbstractBenchmarkTest {

    @Override
    protected Class<?> benchmarkClass() {
        return VirtualThreadRecorderBenchmark.class;
    }

    @Override
    protected List<Class<? extends Profiler>> extraProfilers() {
        return List.of(RecordingProfiler.class, GCProfiler.class);
    }

    @Override
    protected TimeUnit timeUnit() {
        return TimeUnit.SECONDS;
    }

    @Override
    protected List<String> forkJvmArgs() {
        String heap = System.getProperty("rs.vt.heap", "16g");
        return List.of("-Xms" + heap, "-Xmx" + heap, "-XX:+AlwaysPreTouch");
    }

    /** A collapsed cell (no request completes) ends its iteration after 2 minutes instead of JMH's 10. */
    @Override
    protected org.openjdk.jmh.runner.options.TimeValue iterationTimeout() {
        return org.openjdk.jmh.runner.options.TimeValue.minutes(2);
    }

    @Override
    protected void beforeRun() {
        if (Boolean.getBoolean("rs.append")) {
            return;
        }
        try {
            Files.deleteIfExists(CsvWriter.metricsPath(VirtualThreadRecorderBenchmark.SIDE_FIGURE,
                    VirtualThreadRecorderBenchmark.SIDE_FILE));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
