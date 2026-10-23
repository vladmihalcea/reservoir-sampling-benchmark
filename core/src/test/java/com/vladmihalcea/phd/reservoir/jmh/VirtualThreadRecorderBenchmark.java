package com.vladmihalcea.phd.reservoir.jmh;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.common.timing.RecordingTimings;
import com.vladmihalcea.phd.reservoir.common.timing.RecordingTimings.Measurement;
import com.vladmihalcea.phd.reservoir.framework.MicrometerSummary;
import com.vladmihalcea.phd.reservoir.framework.PrometheusSummaryCKMS;
import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import org.HdrHistogram.Histogram;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Experiment V6 of the journal extension: what a shared latency recorder costs a server that handles
 * thousands of concurrent requests on virtual threads, compared with the 200 platform threads of a default
 * Tomcat connector. The survey's E2 swept 1 to 16 writers that did nothing but record, and V3a (the
 * {@code VirtualThreadUpdateExperimentTest}) used virtual writers that never block, so only the carriers made
 * progress. Here every client behaves like a request handler:
 * <ol>
 *   <li>it blocks for {@code ioMicros} in {@link Thread#sleep(Duration)}, the stand-in for a database or HTTP
 *       call, which unmounts a virtual thread from its carrier;</li>
 *   <li>it does a little CPU work ({@code rs.vt.cpuTokens}, 500 by default, about 1-2 &micro;s);</li>
 *   <li>it records its own response time into the one recorder that all clients share, as a timer of
 *       Dropwizard, FlexyPool, or Micrometer does.</li>
 * </ol>
 * With 200 platform threads and 1 ms of I/O, the request rate is bounded at about 200,000 per second, so even
 * the slowest recorder is idle most of the time. With 10,000 virtual threads, the same I/O allows millions of
 * requests per second, the CPU becomes the bottleneck, and the recorder's update competes with the request
 * work for it.
 * <p>
 * The JMH score is the request throughput (requests per second, {@link #requests()}), compared with the
 * {@code none} recorder of the same cell. Each fork also appends one row to
 * {@code metrics/VirtualThreadRecording/vt-recording.csv} with what JMH cannot report:
 * <ul>
 *   <li>the latency of the {@code update} call itself (p50 to p99.99 and max), measured by every client around
 *       its own update during the measurement iterations, which is the tail latency the recorder adds to a
 *       request;</li>
 *   <li>the exact response-time percentiles of the measurement iterations, and of the whole fork (warm-up included,
 *       the span the recorder itself saw), from lossless per-client histograms, next to the p99, p99.9 and max that
 *       the recorder reports at the end of the fork, which is the live accuracy of each recorder under this load;</li>
 *   <li>the end-to-end time of each request, from its start until its {@code update} returns, which is what the
 *       client waits for. The response time a timer records ends before the {@code update} (a timer cannot record
 *       its own cost), so a recorder whose updates are slow lowers the throughput of the closed loop without raising
 *       the recorded response time: the November 13 Prometheus summary cells had an update p99 of 400 ms and a
 *       response p99 of 1.2 ms, lower than the 22 ms without a recorder, since fewer clients competed for a CPU;</li>
 *   <li>the duration of the scrapes, a platform thread reading p99 every {@code rs.vt.scrapeMillis} (10 s), as a
 *       reporter or a Prometheus endpoint would: their mean, p50, p99, and max, how many took longer than
 *       {@code rs.vt.scrapeTimeoutMillis} (10 s, Prometheus's default {@code scrape_timeout}, after which the server
 *       drops the scrape), and the range of the p99 values those scrapes read;</li>
 *   <li>the process CPU load over the measurement iterations (CPU time over wall time and hardware threads) and the
 *       retained sample count;</li>
 *   <li>the delay of the final read, from the moment the clients were told to stop until the recorder's p99, p99.9,
 *       and max were read. The final read waits for a scrape still in progress, which took up to 37 s with 10,000
 *       platform threads. Micrometer's percentiles read 0 once no value has been recorded for 41 to 59 s (its
 *       window covers only the last 40 to 80 s, {@code MicrometerWindowTest}), and they read 0 in 2 of the November
 *       forks whose final read came after such a scrape, so the delay is recorded next to the reported values, and
 *       so is the p99 of the last scrape, which was read while the clients ran.</li>
 * </ul>
 * Every iteration also publishes its mean update latency, mean response time, scrape time, and CPU load
 * ({@link RecordingTimings}), which the {@link com.vladmihalcea.phd.reservoir.common.timing.RecordingProfiler}
 * reports as JMH secondary results ({@code rec.*}), so they carry JMH's per-fork statistics like the throughput. The
 * means come from striped counters; the percentiles come from per-client histograms that only their own client
 * writes, merged once at the end of the fork. Handing a single-writer recorder over at every iteration instead
 * stalls the cells with 10,000 platform threads: the reader waits for every client's write in progress, and those
 * clients wait for a CPU.
 * <p>
 * The response times of this load are nearly stationary apart from the warm-up, so the whole-fork percentiles are
 * the fair truth for the recorders that span the fork (uniform, the 5-minute sliding window, HdrHistogram, the
 * Prometheus summary without decay) and a close one for the decaying ones. The I/O time is fixed, so the response
 * time has a tail only where the CPU is oversubscribed (thousands of platform threads); the accuracy of the
 * recorders over a heavy-tailed stream under the same concurrency is experiment V7. The four per-client histograms
 * (two digits) cost about 120 KB per client, so the forks run with a 16 GB heap ({@code rs.vt.heap}), which also holds
 * the sliding window's hundreds of millions of retained samples at the highest rates (3 million requests per second
 * over the 90 s of a fork, at 16 bytes per sample).
 *
 * @author Vlad Mihalcea
 */
@State(Scope.Benchmark)
public class VirtualThreadRecorderBenchmark {

    /** Requests per benchmark operation: the method waits until the clients complete this many more. */
    static final int BATCH = 1_000;

    static final int CPU_TOKENS = Integer.getInteger("rs.vt.cpuTokens", 500);
    static final long SCRAPE_MILLIS = Long.getLong("rs.vt.scrapeMillis", 10_000);
    static final long SCRAPE_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(
            Long.getLong("rs.vt.scrapeTimeoutMillis", 10_000));
    private static final long POLL_NANOS = 20_000;
    private static final long HIGHEST_LATENCY = TimeUnit.SECONDS.toNanos(60);

    static final String SIDE_FIGURE = "VirtualThreadRecording";
    static final String SIDE_FILE = "vt-recording.csv";

    @Param({"none", "Dropwizard-ExpDecay", "Dropwizard-LockFreeExpDecay", "Dropwizard-Uniform",
            "Dropwizard-SlidingWindow", "Micrometer-Summary", "Prometheus-CKMS", "Prometheus-Native",
            "HdrHistogram-Recorder", "OpenTelemetry-Explicit", "OpenTelemetry-Exponential"})
    public String recorder;

    @Param({"platform", "virtual"})
    public String threadKind;

    @Param({"200", "1000", "10000"})
    public int clients;

    @Param({"1000", "10000"})
    public int ioMicros;

    private SamplingStrategy target;
    private final LongAdder completed = new LongAdder();
    private volatile boolean running;
    private volatile boolean measuring;
    private Thread[] threads;
    // Per client, written only by that client: the update latency and the response time of the measurement
    // iterations, and the response time of the whole fork (the span the recorder sees).
    private Histogram[] updateLatency;
    private Histogram[] responseTime;
    private Histogram[] endToEndTime;
    private Histogram[] forkResponseTime;
    // The means of the current iteration.
    private final LongAdder iterationUpdateNanos = new LongAdder();
    private final LongAdder iterationResponseNanos = new LongAdder();
    private final LongAdder iterationRequests = new LongAdder();
    private Thread scraper;
    private CountDownLatch stopScraper;
    private final Histogram scrapeLatency = new Histogram(HIGHEST_LATENCY, 2);
    private final Histogram iterationScrapes = new Histogram(HIGHEST_LATENCY, 2);
    private double scrapeP99Min = Double.NaN;
    private double scrapeP99Max = Double.NaN;
    private double scrapeP99Sum;
    private long scrapeP99Count;
    private double lastScrapeP99 = Double.NaN;
    private long awaited;
    private long cpuStart = -1;
    private long wallStart;
    private long cpuEnd;
    private long wallEnd;
    private long iterationCpuStart;
    private long iterationWallStart;

    @Setup(Level.Trial)
    public void startClients() throws InterruptedException {
        target = "none".equals(recorder) ? null
                : StrategyCatalog.create(recorder, StrategyCatalog.DEFAULT_K, Clock.SYSTEM, StrategyCatalog.DEFAULT_SEED);
        running = true;
        threads = new Thread[clients];
        updateLatency = new Histogram[clients];
        responseTime = new Histogram[clients];
        endToEndTime = new Histogram[clients];
        forkResponseTime = new Histogram[clients];
        Thread.Builder builder = "virtual".equals(threadKind)
                ? Thread.ofVirtual().name("vt-client-", 0)
                : Thread.ofPlatform().daemon(true).name("pt-client-", 0);
        // The clients wait behind a gate until all of them exist: a client that records while the others are still
        // being created competes with the thread that creates them, which made a contended cell (10,000 platform
        // threads on a locking reservoir) take longer to start than to measure.
        CountDownLatch started = new CountDownLatch(clients);
        CountDownLatch gate = new CountDownLatch(1);
        Duration io = Duration.ofNanos(TimeUnit.MICROSECONDS.toNanos(ioMicros));
        for (int i = 0; i < clients; i++) {
            Histogram update = new Histogram(HIGHEST_LATENCY, 2);
            Histogram response = new Histogram(HIGHEST_LATENCY, 2);
            Histogram endToEnd = new Histogram(HIGHEST_LATENCY, 2);
            Histogram forkResponse = new Histogram(HIGHEST_LATENCY, 2);
            updateLatency[i] = update;
            responseTime[i] = response;
            endToEndTime[i] = endToEnd;
            forkResponseTime[i] = forkResponse;
            threads[i] = builder.start(() -> clientLoop(io, update, response, endToEnd, forkResponse, started, gate));
        }
        started.await();
        gate.countDown();
        if (target != null) {
            stopScraper = new CountDownLatch(1);
            scraper = Thread.ofPlatform().daemon(true).name("scraper").start(this::scrapeLoop);
        }
    }

    private void clientLoop(Duration io, Histogram update, Histogram response, Histogram endToEnd,
                            Histogram forkResponse, CountDownLatch started, CountDownLatch gate) {
        started.countDown();
        try {
            gate.await();
        } catch (InterruptedException e) {
            return;
        }
        SamplingStrategy shared = target;
        while (running) {
            long start = System.nanoTime();
            try {
                Thread.sleep(io);
            } catch (InterruptedException e) {
                return;
            }
            Blackhole.consumeCPU(CPU_TOKENS);
            long beforeUpdate = System.nanoTime();
            long latency = beforeUpdate - start;
            if (shared != null) {
                shared.update(latency);
            }
            long afterUpdate = System.nanoTime();
            long updateNanos = Math.min(Math.max(afterUpdate - beforeUpdate, 1), HIGHEST_LATENCY);
            long responseNanos = Math.min(Math.max(latency, 1), HIGHEST_LATENCY);
            forkResponse.recordValue(responseNanos);
            if (measuring) {
                update.recordValue(updateNanos);
                response.recordValue(responseNanos);
                endToEnd.recordValue(Math.min(Math.max(afterUpdate - start, 1), HIGHEST_LATENCY));
            }
            iterationUpdateNanos.add(updateNanos);
            iterationResponseNanos.add(responseNanos);
            iterationRequests.increment();
            completed.increment();
        }
    }

    private void scrapeLoop() {
        while (running) {
            try {
                if (stopScraper.await(SCRAPE_MILLIS, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                return;
            }
            // A scrape counts when it starts during a measurement iteration, so a long scrape that ends after the last
            // iteration, the one the final read waits for, is not lost.
            boolean counted = measuring;
            long start = System.nanoTime();
            double p99 = target.valueAtQuantile(0.99);
            long elapsed = System.nanoTime() - start;
            if (counted) {
                synchronized (scrapeLatency) {
                    long recorded = Math.min(Math.max(elapsed, 1), HIGHEST_LATENCY);
                    scrapeLatency.recordValue(recorded);
                    if (measuring) {
                        iterationScrapes.recordValue(recorded);
                    }
                    if (!Double.isNaN(p99)) {
                        scrapeP99Min = Double.isNaN(scrapeP99Min) ? p99 : Math.min(scrapeP99Min, p99);
                        scrapeP99Max = Double.isNaN(scrapeP99Max) ? p99 : Math.max(scrapeP99Max, p99);
                        scrapeP99Sum += p99;
                        scrapeP99Count++;
                        lastScrapeP99 = p99;
                    }
                }
            }
        }
    }

    @Setup(Level.Iteration)
    public void startIteration(IterationParams params) {
        iterationUpdateNanos.reset();
        iterationResponseNanos.reset();
        iterationRequests.reset();
        synchronized (scrapeLatency) {
            iterationScrapes.reset();
        }
        // Requests completed between iterations, while JMH was not calling requests(), are not counted.
        awaited = completed.sum();
        measuring = params.getType() == IterationType.MEASUREMENT;
        iterationCpuStart = processCpuNanos();
        iterationWallStart = System.nanoTime();
        if (measuring && cpuStart < 0) {
            cpuStart = iterationCpuStart;
            wallStart = iterationWallStart;
        }
    }

    @TearDown(Level.Iteration)
    public void endIteration() {
        long cpu = processCpuNanos();
        long wall = System.nanoTime();
        if (measuring) {
            cpuEnd = cpu;
            wallEnd = wall;
        }
        long requests = iterationRequests.sum();
        double cpuLoad = cpu < 0 || wall <= iterationWallStart ? Double.NaN
                : (cpu - iterationCpuStart) / ((double) (wall - iterationWallStart)
                * Runtime.getRuntime().availableProcessors());
        List<Measurement> measurements = new ArrayList<>();
        measurements.add(new Measurement("rec.update.mean.ns",
                requests == 0 ? Double.NaN : (double) iterationUpdateNanos.sum() / requests, "ns", false));
        measurements.add(new Measurement("rec.response.mean.ms",
                requests == 0 ? Double.NaN : iterationResponseNanos.sum() / 1e6 / requests, "ms", false));
        measurements.add(new Measurement("rec.cpu.load", cpuLoad, "ratio", false));
        synchronized (scrapeLatency) {
            if (iterationScrapes.getTotalCount() > 0) {
                measurements.add(new Measurement("rec.scrape.ms", iterationScrapes.getMean() / 1e6, "ms", false));
            }
        }
        RecordingTimings.publish(measurements);
        measuring = false;
    }

    /**
     * One operation is {@link #BATCH} completed requests of all clients together, so the throughput score is in
     * requests per second (JMH divides by {@link OperationsPerInvocation}).
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @OperationsPerInvocation(BATCH)
    public long requests() {
        long goal = awaited + BATCH;
        long done;
        while ((done = completed.sum()) < goal) {
            LockSupport.parkNanos(POLL_NANOS);
            if (Thread.interrupted()) {
                // JMH's iteration timeout (VirtualThreadRecorderBenchmarkTest) interrupts a cell that collapsed.
                break;
            }
        }
        awaited = goal;
        return done;
    }

    @TearDown(Level.Trial)
    public void stopClients() throws InterruptedException {
        // The share of all hardware threads the process used over the measurement iterations.
        double cpuLoad = cpuStart < 0 || wallEnd <= wallStart ? Double.NaN
                : (cpuEnd - cpuStart) / ((double) (wallEnd - wallStart) * Runtime.getRuntime().availableProcessors());
        long stopping = System.nanoTime();
        running = false;
        if (scraper != null) {
            // A latch, not an interrupt, so a scrape in progress completes. An interrupt that lands inside a
            // Prometheus collect (Buffer.run, prometheus-metrics-core 1.4.3, waiting for the observations still in
            // flight) leaves the buffered observations unapplied, and every later collect, such as the reported p99
            // below, then spins forever: on 13 Nov this hung both servers for 7 hours in the cell with 10,000
            // platform clients and 10 ms of I/O. The final read below therefore also waits for the scrape, instead of
            // running a second collect next to it.
            stopScraper.countDown();
            scraper.join();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        Histogram update = new Histogram(HIGHEST_LATENCY, 2);
        Histogram response = new Histogram(HIGHEST_LATENCY, 2);
        Histogram endToEnd = new Histogram(HIGHEST_LATENCY, 2);
        Histogram forkResponse = new Histogram(HIGHEST_LATENCY, 2);
        int progressed = 0;
        for (int i = 0; i < clients; i++) {
            update.add(updateLatency[i]);
            response.add(responseTime[i]);
            endToEnd.add(endToEndTime[i]);
            forkResponse.add(forkResponseTime[i]);
            if (responseTime[i].getTotalCount() > 0) {
                progressed++;
            }
        }
        double reportedP99 = Double.NaN;
        double reportedP999 = Double.NaN;
        double reportedMax = Double.NaN;
        double finalReadDelayMillis = Double.NaN;
        String retained = "";
        if (target != null) {
            reportedP99 = target.valueAtQuantile(0.99);
            reportedP999 = target.valueAtQuantile(0.999);
            reportedMax = reportedMax(target);
            finalReadDelayMillis = (System.nanoTime() - stopping) / 1e6;
            retained = SideOutput.retained(target);
        }
        long scrapes = scrapeLatency.getTotalCount();
        long slowScrapes = scrapes == 0 ? 0
                : scrapeLatency.getCountBetweenValues(scrapeLatency.nextNonEquivalentValue(SCRAPE_TIMEOUT_NANOS),
                scrapeLatency.getMaxValue());
        SideOutput.append(CsvWriter.metricsPath(SIDE_FIGURE, SIDE_FILE), SIDE_HEADER,
                recorder, threadKind, clients, ioMicros, CPU_TOKENS, ProcessHandle.current().pid(),
                response.getTotalCount(), progressed, cpuLoad,
                update.getValueAtPercentile(50), update.getValueAtPercentile(90), update.getValueAtPercentile(99),
                update.getValueAtPercentile(99.9), update.getValueAtPercentile(99.99), update.getMaxValue(),
                response.getValueAtPercentile(50), response.getValueAtPercentile(99),
                response.getValueAtPercentile(99.9), response.getMaxValue(),
                forkResponse.getTotalCount(), forkResponse.getValueAtPercentile(50),
                forkResponse.getValueAtPercentile(99), forkResponse.getValueAtPercentile(99.9), forkResponse.getMaxValue(),
                reportedP99, reportedP999, reportedMax, retained,
                scrapes, scrapes == 0 ? "" : scrapeLatency.getMean(),
                scrapes == 0 ? "" : scrapeLatency.getMaxValue(),
                scrapeP99Count == 0 ? "" : scrapeP99Min, scrapeP99Count == 0 ? "" : scrapeP99Sum / scrapeP99Count,
                scrapeP99Count == 0 ? "" : scrapeP99Max,
                endToEnd.getValueAtPercentile(50), endToEnd.getValueAtPercentile(99),
                endToEnd.getValueAtPercentile(99.9), endToEnd.getMaxValue(),
                scrapes == 0 ? "" : scrapeLatency.getValueAtPercentile(50),
                scrapes == 0 ? "" : scrapeLatency.getValueAtPercentile(99),
                target == null ? "" : slowScrapes,
                Double.isNaN(lastScrapeP99) ? "" : lastScrapeP99, finalReadDelayMillis);
    }

    /**
     * The maximum the recorder reports: Micrometer's decaying max, nothing for a Prometheus summary (which keeps no
     * maximum; its nearest tracked quantile would be p99.9), and the top of the reservoir or histogram otherwise.
     */
    private static double reportedMax(SamplingStrategy target) {
        if (target instanceof MicrometerSummary micrometer) {
            return micrometer.max();
        }
        if (target instanceof PrometheusSummaryCKMS) {
            return Double.NaN;
        }
        return target.valueAtQuantile(1.0);
    }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        return os instanceof com.sun.management.OperatingSystemMXBean sun ? sun.getProcessCpuTime() : -1;
    }

    static final String[] SIDE_HEADER = {"recorder", "thread_kind", "clients", "io_micros", "cpu_tokens", "fork_pid",
            "measured_requests", "clients_progressed", "process_cpu_load",
            "update_p50_ns", "update_p90_ns", "update_p99_ns", "update_p999_ns", "update_p9999_ns", "update_max_ns",
            "response_p50_ns", "response_p99_ns", "response_p999_ns", "response_max_ns",
            "fork_requests", "fork_response_p50_ns", "fork_response_p99_ns", "fork_response_p999_ns",
            "fork_response_max_ns", "reported_p99_ns", "reported_p999_ns", "reported_max_ns", "retained_samples",
            "scrapes", "scrape_mean_ns", "scrape_max_ns", "scrape_p99_min_ns", "scrape_p99_mean_ns",
            "scrape_p99_max_ns", "e2e_p50_ns", "e2e_p99_ns", "e2e_p999_ns", "e2e_max_ns",
            "scrape_p50_ns", "scrape_p99_ns", "scrapes_over_timeout", "last_scrape_p99_ns", "final_read_delay_ms"};

    /** Appends one row per fork to a side CSV, writing the header when the file is new. */
    static final class SideOutput {

        private SideOutput() {
        }

        static synchronized void append(Path file, String[] header, Object... cells) {
            try {
                Files.createDirectories(file.getParent());
                StringBuilder row = new StringBuilder();
                if (!Files.exists(file)) {
                    row.append(String.join(",", header)).append('\n');
                }
                for (int i = 0; i < cells.length; i++) {
                    if (i > 0) {
                        row.append(',');
                    }
                    Object cell = cells[i];
                    row.append(cell instanceof Double d ? String.format(Locale.ROOT, "%.6g", d) : String.valueOf(cell));
                }
                row.append('\n');
                Files.writeString(file, row, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** The retained sample count of the Dropwizard reservoirs, blank for the histograms. */
        static String retained(SamplingStrategy strategy) {
            if (strategy instanceof com.vladmihalcea.phd.reservoir.framework.DropwizardExpDecay s) {
                return String.valueOf(s.retained());
            }
            if (strategy instanceof com.vladmihalcea.phd.reservoir.framework.DropwizardLockFreeExpDecay s) {
                return String.valueOf(s.retained());
            }
            if (strategy instanceof com.vladmihalcea.phd.reservoir.framework.DropwizardUniform s) {
                return String.valueOf(s.retained());
            }
            if (strategy instanceof com.vladmihalcea.phd.reservoir.framework.DropwizardSlidingTimeWindow s) {
                return String.valueOf(s.retained());
            }
            return "";
        }
    }
}
