package com.vladmihalcea.phd.reservoir.common.telemetry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.DoubleSummaryStatistics;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleUnaryOperator;
import java.util.function.ToDoubleFunction;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * Samples the CPU telemetry of the local host once per second, so that the benchmark results
 * show whether the CPU held its clocks during every iteration or got throttled.
 * <p>
 * On Windows, the samples come from the performance counters that {@code typeperf} reports,
 * and on Linux, from the {@code /proc} and {@code /sys} file systems.
 * On other operating systems, the sampler collects no samples.
 *
 * @author Vlad Mihalcea
 */
public class TelemetrySampler implements AutoCloseable {

    public enum Aggregation {
        AVG(DoubleSummaryStatistics::getAverage),
        MIN(DoubleSummaryStatistics::getMin),
        MAX(DoubleSummaryStatistics::getMax),
        SUM(DoubleSummaryStatistics::getSum);

        private final ToDoubleFunction<DoubleSummaryStatistics> function;

        Aggregation(ToDoubleFunction<DoubleSummaryStatistics> function) {
            this.function = function;
        }

        double apply(DoubleSummaryStatistics statistics) {
            return function.applyAsDouble(statistics);
        }
    }

    /**
     * The aggregation of a metric applies to the samples of an iteration and, in the JMH results,
     * to the iterations of a benchmark, e.g., the performance limit reports the lowest value,
     * and the thermal throttles report the number of throttling events.
     * The CPU power, the package power of Intel RAPL, and its long-term limit (PL1), above which the CPU lowers
     * its clock, are reported on Linux only.
     */
    public enum Metric {
        CPU_MHZ("cpu.mhz", "MHz", Aggregation.AVG),
        CPU_UTILIZATION("cpu.util", "%", Aggregation.AVG),
        CPU_PERFORMANCE_LIMIT("cpu.limit", "%", Aggregation.MIN),
        THERMAL_LIMIT("thermal.limit", "%", Aggregation.MIN),
        TEMPERATURE("temp", "degC", Aggregation.MAX),
        THERMAL_THROTTLES("thermal.throttles", "#", Aggregation.SUM),
        CPU_POWER("cpu.power", "W", Aggregation.AVG),
        CPU_POWER_LIMIT("cpu.power.limit", "W", Aggregation.MIN);

        private final String label;
        private final String unit;
        private final Aggregation aggregation;

        Metric(String label, String unit, Aggregation aggregation) {
            this.label = label;
            this.unit = unit;
            this.aggregation = aggregation;
        }

        public String label() {
            return label;
        }

        public String unit() {
            return unit;
        }

        public Aggregation aggregation() {
            return aggregation;
        }

        public static Metric of(String label) {
            return Arrays.stream(values())
                .filter(metric -> metric.label.equals(label))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown metric: " + label));
        }
    }

    private record Sample(long time, double[] values) {
    }

    private static final long RETENTION_MILLIS = TimeUnit.HOURS.toMillis(1);

    private final Deque<Sample> samples = new ArrayDeque<>();

    /**
     * @param maxDuration the duration after which the sampler stops on its own,
     *                    or {@code null} to sample until the sampler is closed
     */
    public static TelemetrySampler start(Duration maxDuration) {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.startsWith("windows")) {
                return new WindowsSampler(maxDuration);
            }
            if (os.startsWith("linux")) {
                return new LinuxSampler();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start the telemetry sampler", e);
        }
        return new TelemetrySampler();
    }

    /**
     * @return the time in milliseconds of the sampler clock, which marks the start of an iteration
     */
    public long now() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    /**
     * Aggregates the samples taken after the given time of the sampler clock.
     */
    public synchronized Map<Metric, Double> summary(long from) {
        Map<Metric, Double> summary = new EnumMap<>(Metric.class);
        for (Metric metric : Metric.values()) {
            DoubleSummaryStatistics statistics = samples.stream()
                .filter(sample -> sample.time() > from)
                .mapToDouble(sample -> sample.values()[metric.ordinal()])
                .filter(value -> !Double.isNaN(value))
                .summaryStatistics();
            if (statistics.getCount() > 0) {
                summary.put(metric, metric.aggregation().apply(statistics));
            }
        }
        return summary;
    }

    /**
     * @param statistics the per-metric statistics of one sample, e.g., the temperatures of several thermal zones
     */
    protected synchronized void add(DoubleSummaryStatistics[] statistics) {
        double[] values = new double[statistics.length];
        for (Metric metric : Metric.values()) {
            DoubleSummaryStatistics metricStatistics = statistics[metric.ordinal()];
            values[metric.ordinal()] = metricStatistics.getCount() > 0 ?
                metric.aggregation().apply(metricStatistics) :
                Double.NaN;
        }
        long time = now();
        samples.addLast(new Sample(time, values));
        while (samples.getFirst().time() < time - RETENTION_MILLIS) {
            samples.removeFirst();
        }
    }

    protected static DoubleSummaryStatistics[] newStatistics() {
        DoubleSummaryStatistics[] statistics = new DoubleSummaryStatistics[Metric.values().length];
        Arrays.setAll(statistics, i -> new DoubleSummaryStatistics());
        return statistics;
    }

    @Override
    public void close() {
    }

    /**
     * Reads the Processor Information and Thermal Zone Information performance counters.
     * The effective CPU frequency is the nominal frequency scaled by the {@code % Processor Performance} counter,
     * as in the Windows Task Manager, while the {@code % Performance Limit} and {@code % Passive Limit} counters
     * drop below 100% when the firmware or the operating system limits the CPU performance.
     */
    static class WindowsSampler extends TelemetrySampler {

        record Counter(String path, Metric metric, DoubleUnaryOperator conversion) {

            /**
             * @return whether the counter has one instance per thermal zone
             */
            boolean wildcard() {
                return path.contains("(*)");
            }
        }

        private final List<Counter> counters;
        private final Process process;

        WindowsSampler(Duration maxDuration) throws IOException {
            counters = counters(nominalMhz());

            List<String> command = new ArrayList<>(List.of("typeperf"));
            counters.forEach(counter -> command.add(counter.path()));
            command.addAll(List.of("-si", "1"));
            if (maxDuration != null) {
                // Bounds the typeperf process even if the JVM gets killed without running the shutdown hooks
                command.addAll(List.of("-sc", String.valueOf(maxDuration.toSeconds())));
            }
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));

            Thread reader = new Thread(this::read, "telemetry-sampler");
            reader.setDaemon(true);
            reader.start();
        }

        static List<Counter> counters(double nominalMhz) {
            return List.of(
                new Counter("\\Processor Information(_Total)\\% Processor Performance", Metric.CPU_MHZ, value -> value * nominalMhz / 100),
                new Counter("\\Processor Information(_Total)\\% Processor Time", Metric.CPU_UTILIZATION, value -> value),
                new Counter("\\Processor Information(_Total)\\% Performance Limit", Metric.CPU_PERFORMANCE_LIMIT, value -> value),
                new Counter("\\Thermal Zone Information(*)\\% Passive Limit", Metric.THERMAL_LIMIT, value -> value),
                // The thermal zones report Kelvin, and zero when the zone has no sensor
                new Counter("\\Thermal Zone Information(*)\\Temperature", Metric.TEMPERATURE, value -> value > 0 ? value - 273.15 : Double.NaN)
            );
        }

        /**
         * typeperf prints a CSV header with the counter paths, then one CSV row per second, whose values follow
         * the order of the requested counters, with one value per thermal zone for a wildcard counter.
         * The header cannot be trusted: typeperf sometimes leaves a counter out of it while still printing its values,
         * which would shift every later column, so the columns follow from the counter order and the row width.
         */
        private void read() {
            try (BufferedReader reader = process.inputReader()) {
                Counter[] columns = null;
                for (String line; (line = reader.readLine()) != null; ) {
                    if (!line.startsWith("\"") || line.startsWith("\"(PDH")) {
                        continue;
                    }
                    String[] cells = cells(line);
                    if (columns == null) {
                        columns = columns(counters, cells.length);
                    }
                    add(sample(columns, cells));
                }
            } catch (IOException ignore) {
                // The stream closes when the sampler stops
            }
        }

        static String[] cells(String line) {
            return line.substring(1, line.length() - 1).split("\",\"", -1);
        }

        /**
         * @param width the number of cells of a row, including the timestamp
         * @return the counter of every cell, or none if the width does not fit the counters
         */
        static Counter[] columns(List<Counter> counters, int width) {
            Counter[] columns = new Counter[width];
            int wildcards = (int) counters.stream().filter(Counter::wildcard).count();
            int instanceColumns = width - 1 - (counters.size() - wildcards);
            if (instanceColumns < 0 || (wildcards == 0 ? instanceColumns > 0 : instanceColumns % wildcards != 0)) {
                return columns;
            }
            int instances = wildcards == 0 ? 0 : instanceColumns / wildcards;
            int column = 1;
            for (Counter counter : counters) {
                for (int i = 0; i < (counter.wildcard() ? instances : 1); i++) {
                    columns[column++] = counter;
                }
            }
            return columns;
        }

        static DoubleSummaryStatistics[] sample(Counter[] columns, String[] cells) {
            DoubleSummaryStatistics[] statistics = newStatistics();
            for (int i = 1; i < cells.length && i < columns.length; i++) {
                Counter counter = columns[i];
                double value = counter != null ? counter.conversion().applyAsDouble(parse(cells[i])) : Double.NaN;
                if (!Double.isNaN(value)) {
                    statistics[counter.metric().ordinal()].accept(value);
                }
            }
            return statistics;
        }

        private static double parse(String cell) {
            try {
                return Double.parseDouble(cell.trim());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        /**
         * @return the nominal CPU frequency, which Windows measures at boot time
         */
        private static double nominalMhz() throws IOException {
            Process reg = new ProcessBuilder(
                "reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "/v", "~MHz"
            ).start();
            try (BufferedReader reader = reg.inputReader()) {
                return reader.lines()
                    .filter(line -> line.contains("~MHz"))
                    .map(line -> line.trim().split("\\s+"))
                    .map(tokens -> Integer.decode(tokens[tokens.length - 1]))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Cannot read the nominal CPU frequency"));
            }
        }

        @Override
        public void close() {
            process.destroy();
        }
    }

    /**
     * Reads the clock of the running cores from the {@code APERF} and {@code MPERF} counters of every CPU,
     * the utilization from {@code /proc/stat}, the temperature from the CPU sensor drivers, e.g., {@code k10temp}
     * for AMD and {@code coretemp} for Intel, the thermal throttling events from the counters of the
     * {@code therm_throt} driver of Intel CPUs, and the package power from the Intel RAPL energy counters.
     * Linux has no counterpart of the Windows performance and passive limits, so the throttling events
     * take their place on Intel CPUs, and the package power shows how far the CPU stays from its power limit.
     * <p>
     * The {@code cpufreq} files are no measure of the clock: on the Xeon E-2276G servers, {@code scaling_cur_freq}
     * and {@code cpuinfo_avg_freq} read either 800 MHz or about 4.7 GHz on every CPU, busy or idle, so their mean
     * says nothing about the cores that run the benchmark. They remain the fallback where the counters
     * cannot be read, i.e., without root, without the {@code msr} module, or without the base frequency.
     */
    static class LinuxSampler extends TelemetrySampler {

        private static final Path CPU_DIRECTORY = Path.of("/sys/devices/system/cpu");

        private static final Path MSR_DIRECTORY = Path.of("/dev/cpu");

        private static final Path POWERCAP_DIRECTORY = Path.of("/sys/class/powercap");

        /**
         * The zones of the MSR interface of RAPL, not the {@code intel-rapl-mmio} zones of some Intel CPUs,
         * which measure the same packages.
         */
        private static final String POWER_ZONES = "intel-rapl:*";

        private static final Set<String> CPU_SENSORS = Set.of("k10temp", "zenpower", "coretemp", "cpu_thermal");

        /**
         * The model-specific registers of the maximum and the actual performance clock counters:
         * while a CPU runs, MPERF counts at its base frequency and APERF at its actual frequency,
         * and both stop while the CPU idles.
         */
        static final int IA32_MPERF = 0xE7;

        static final int IA32_APERF = 0xE8;

        /**
         * The APERF and MPERF counters of every CPU, read through the {@code msr} driver,
         * whose file offset selects the register.
         */
        static final class ClockCounters implements AutoCloseable {

            private final List<FileChannel> channels;

            private final double baseMhz;

            private final ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);

            private ClockCounters(List<FileChannel> channels, double baseMhz) {
                this.channels = channels;
                this.baseMhz = baseMhz;
            }

            /**
             * @return the counters of every CPU, or {@code null} if they cannot be read
             */
            static ClockCounters open(Path msrDirectory, Path cpuDirectory) {
                List<FileChannel> channels = new ArrayList<>();
                try {
                    double baseMhz = read(cpuDirectory.resolve("cpu0/cpufreq/base_frequency")) / 1000;
                    for (Path cpu : list(cpuDirectory, "cpu[0-9]*")) {
                        String number = cpu.getFileName().toString().substring("cpu".length());
                        channels.add(FileChannel.open(msrDirectory.resolve(number).resolve("msr"), StandardOpenOption.READ));
                    }
                    ClockCounters counters = new ClockCounters(channels, baseMhz);
                    if (channels.isEmpty() || baseMhz <= 0) {
                        counters.close();
                        return null;
                    }
                    counters.readAll();
                    return counters;
                } catch (IOException | RuntimeException e) {
                    channels.forEach(LinuxSampler::closeQuietly);
                    return null;
                }
            }

            /**
             * @return the APERF counters of every CPU, followed by their MPERF counters
             */
            long[] readAll() throws IOException {
                long[] counters = new long[2 * channels.size()];
                for (int i = 0; i < channels.size(); i++) {
                    counters[i] = readRegister(channels.get(i), IA32_APERF);
                    counters[channels.size() + i] = readRegister(channels.get(i), IA32_MPERF);
                }
                return counters;
            }

            private long readRegister(FileChannel channel, int register) throws IOException {
                buffer.clear();
                if (channel.read(buffer, register) != Long.BYTES) {
                    throw new IOException("Cannot read the model-specific register 0x" + Integer.toHexString(register));
                }
                return buffer.flip().getLong();
            }

            double baseMhz() {
                return baseMhz;
            }

            @Override
            public void close() {
                channels.forEach(LinuxSampler::closeQuietly);
            }
        }

        /**
         * The clock of the running cores between two readings: the base frequency scaled by the APERF increments
         * over the MPERF increments of all CPUs, so every CPU weighs by its running time, as in the {@code Bzy_MHz}
         * column of {@code turbostat}. Idle CPUs, whose counters stand still, do not lower it.
         *
         * @param previous the APERF counters of every CPU, followed by their MPERF counters
         * @param current  the same counters, read later
         * @return the clock in MHz, or NaN without a previous reading or if no CPU ran
         */
        static double busyClock(long[] previous, long[] current, double baseMhz) {
            if (previous == null || previous.length != current.length) {
                return Double.NaN;
            }
            int cpus = current.length / 2;
            long aperf = 0, mperf = 0;
            for (int i = 0; i < cpus; i++) {
                aperf += current[i] - previous[i];
                mperf += current[cpus + i] - previous[cpus + i];
            }
            return mperf > 0 ? baseMhz * aperf / mperf : Double.NaN;
        }

        /**
         * The energy counter of an Intel RAPL package, which wraps around at its maximum range,
         * and the long-term power limit of the package (PL1) in watts, or NaN if the zone has none.
         */
        record PowerZone(Path energy, long maxRange, double limit) {
        }

        /**
         * @return the long-term power limit of all packages in watts, or NaN if a package has none
         */
        static double powerLimit(List<PowerZone> zones) {
            return zones.isEmpty() ? Double.NaN : zones.stream().mapToDouble(PowerZone::limit).sum();
        }

        /**
         * @return the package zones of the powercap framework, e.g., {@code intel-rapl:0} named {@code package-0},
         * without their {@code core} and {@code dram} subzones, or none if the energy counters cannot be read,
         * which since Linux 5.10 requires root
         */
        static List<PowerZone> packageZones(Path powercapDirectory, String glob) {
            List<PowerZone> zones = new ArrayList<>();
            try {
                for (Path zone : list(powercapDirectory, glob)) {
                    Path name = zone.resolve("name");
                    Path energy = zone.resolve("energy_uj");
                    if (Files.exists(name) && Files.readString(name).trim().startsWith("package-")) {
                        read(energy);
                        Path limit = zone.resolve("constraint_0_power_limit_uw");
                        zones.add(new PowerZone(energy, (long) read(zone.resolve("max_energy_range_uj")),
                            Files.exists(limit) ? read(limit) / 1_000_000 : Double.NaN));
                    }
                }
            } catch (IOException | RuntimeException e) {
                return List.of();
            }
            return zones;
        }

        static long[] energies(List<PowerZone> zones) throws IOException {
            long[] energies = new long[zones.size()];
            for (int i = 0; i < zones.size(); i++) {
                energies[i] = (long) read(zones.get(i).energy());
            }
            return energies;
        }

        /**
         * @return the power of all packages in watts between two readings of their energy counters,
         * which lie the given nanoseconds apart, or NaN without a previous reading
         */
        static double power(List<PowerZone> zones, long[] previous, long[] current, long nanos) {
            if (previous == null || previous.length != current.length || nanos <= 0) {
                return Double.NaN;
            }
            double microjoules = 0;
            for (int i = 0; i < current.length; i++) {
                long delta = current[i] - previous[i];
                microjoules += delta >= 0 ? delta : delta + zones.get(i).maxRange();
            }
            return microjoules * 1000 / nanos;
        }

        /**
         * The thermal throttling counters of one logical CPU. The kernel keeps them per logical CPU,
         * but the core counter of a CPU can count the same events as the one of its sibling,
         * and the package counter the same events as the ones of the other CPUs of the package.
         *
         * @param core         the core of the CPU, unique across packages
         * @param pkg          the package of the CPU
         * @param coreCount    the throttling events of the core
         * @param packageCount the throttling events of the package, or -1 if the CPU has no package thermal status
         */
        record ThrottleCounts(String core, String pkg, long coreCount, long packageCount) {
        }

        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "telemetry-sampler");
            thread.setDaemon(true);
            return thread;
        });

        private long[] previousCpuTimes;

        private Map<String, ThrottleCounts> previousThrottleCounts;

        private volatile ClockCounters clockCounters = ClockCounters.open(MSR_DIRECTORY, CPU_DIRECTORY);

        private long[] previousClockCounters;

        private final List<PowerZone> powerZones = packageZones(POWERCAP_DIRECTORY, POWER_ZONES);

        private long[] previousEnergies;

        private long previousEnergyTime;

        LinuxSampler() {
            // The fork log and the agent output record the sources of the metrics
            System.out.printf("Telemetry: CPU clock from %s; package power %s%n",
                clockCounters != null ? "the APERF and MPERF counters of the running cores" : "cpufreq, the mean of all CPUs",
                powerZones.isEmpty() ? "not available" : "from Intel RAPL");
            executor.scheduleAtFixedRate(this::sample, 0, 1, TimeUnit.SECONDS);
        }

        private void sample() {
            try {
                DoubleSummaryStatistics[] statistics = newStatistics();
                double clock = clock();
                if (!Double.isNaN(clock)) {
                    statistics[Metric.CPU_MHZ.ordinal()].accept(clock);
                }
                double power = power();
                if (!Double.isNaN(power)) {
                    statistics[Metric.CPU_POWER.ordinal()].accept(power);
                    double limit = powerLimit(powerZones);
                    if (!Double.isNaN(limit)) {
                        statistics[Metric.CPU_POWER_LIMIT.ordinal()].accept(limit);
                    }
                }
                for (Path hwmon : list(Path.of("/sys/class/hwmon"), "hwmon*")) {
                    Path name = hwmon.resolve("name");
                    if (Files.exists(name) && CPU_SENSORS.contains(Files.readString(name).trim())) {
                        for (Path temperature : list(hwmon, "temp*_input")) {
                            statistics[Metric.TEMPERATURE.ordinal()].accept(read(temperature) / 1000);
                        }
                    }
                }
                double throttles = throttleEvents();
                if (!Double.isNaN(throttles)) {
                    statistics[Metric.THERMAL_THROTTLES.ordinal()].accept(throttles);
                }
                double utilization = cpuUtilization();
                if (!Double.isNaN(utilization)) {
                    statistics[Metric.CPU_UTILIZATION.ordinal()].accept(utilization);
                    add(statistics);
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("Cannot sample the CPU telemetry: " + e);
            }
        }

        /**
         * @return the clock of the running cores since the previous sample, or NaN for the first sample;
         * without the counters, the mean current frequency of all CPUs
         */
        private double clock() throws IOException {
            ClockCounters counters = clockCounters;
            if (counters != null) {
                try {
                    long[] current = counters.readAll();
                    double clock = busyClock(previousClockCounters, current, counters.baseMhz());
                    previousClockCounters = current;
                    return clock;
                } catch (IOException e) {
                    System.err.println("Cannot read the APERF and MPERF counters, so the clock comes from cpufreq from now on: " + e);
                    counters.close();
                    clockCounters = null;
                }
            }
            DoubleSummaryStatistics frequencies = new DoubleSummaryStatistics();
            for (Path cpu : list(CPU_DIRECTORY, "cpu[0-9]*")) {
                Path frequency = cpu.resolve("cpufreq/scaling_cur_freq");
                if (Files.exists(frequency)) {
                    frequencies.accept(read(frequency) / 1000);
                }
            }
            return frequencies.getCount() > 0 ? frequencies.getAverage() : Double.NaN;
        }

        /**
         * @return the package power since the previous sample, or NaN for the first sample and without RAPL
         */
        private double power() {
            if (powerZones.isEmpty()) {
                return Double.NaN;
            }
            try {
                long time = System.nanoTime();
                long[] energies = energies(powerZones);
                double power = power(powerZones, previousEnergies, energies, time - previousEnergyTime);
                previousEnergies = energies;
                previousEnergyTime = time;
                return power;
            } catch (IOException | RuntimeException e) {
                previousEnergies = null;
                System.err.println("Cannot read the RAPL energy counters: " + e);
                return Double.NaN;
            }
        }

        /**
         * @return the CPU utilization since the previous sample, or NaN for the first sample
         */
        private double cpuUtilization() throws IOException {
            String[] fields = Files.readAllLines(Path.of("/proc/stat")).get(0).trim().split("\\s+");
            // user, nice, system, idle, iowait, irq, softirq, steal
            long[] cpuTimes = Arrays.stream(fields).skip(1).limit(8).mapToLong(Long::parseLong).toArray();
            long[] previous = previousCpuTimes;
            previousCpuTimes = cpuTimes;
            if (previous == null) {
                return Double.NaN;
            }
            long total = LongStream.of(cpuTimes).sum() - LongStream.of(previous).sum();
            long idle = cpuTimes[3] + cpuTimes[4] - previous[3] - previous[4];
            return total > 0 ? 100.0 * (total - idle) / total : Double.NaN;
        }

        /**
         * @return the thermal throttling events since the previous sample, or NaN for the first sample,
         * on CPUs without throttling counters, e.g., AMD CPUs, and if the counters cannot be read
         */
        private double throttleEvents() {
            Map<String, ThrottleCounts> previous = previousThrottleCounts;
            try {
                previousThrottleCounts = throttleCounts(CPU_DIRECTORY);
            } catch (IOException | RuntimeException e) {
                previousThrottleCounts = null;
                System.err.println("Cannot read the thermal throttling counters: " + e);
                return Double.NaN;
            }
            return throttleEvents(previous, previousThrottleCounts);
        }

        /**
         * Reads the {@code thermal_throttle/core_throttle_count} and {@code thermal_throttle/package_throttle_count}
         * counters of every CPU, with the core and the package that the {@code topology} folder of the CPU names.
         *
         * @return the counters by CPU, or none if the CPUs have no throttling counters
         */
        static Map<String, ThrottleCounts> throttleCounts(Path cpuDirectory) throws IOException {
            Map<String, ThrottleCounts> counts = new HashMap<>();
            for (Path cpu : list(cpuDirectory, "cpu[0-9]*")) {
                Path throttle = cpu.resolve("thermal_throttle");
                Path coreCounter = throttle.resolve("core_throttle_count");
                Path packageCounter = throttle.resolve("package_throttle_count");
                Path packageId = cpu.resolve("topology").resolve("physical_package_id");
                Path coreId = cpu.resolve("topology").resolve("core_id");
                if (Files.exists(coreCounter) && Files.exists(packageId) && Files.exists(coreId)) {
                    String pkg = Files.readString(packageId).trim();
                    counts.put(cpu.getFileName().toString(), new ThrottleCounts(
                        pkg + "/" + Files.readString(coreId).trim(),
                        pkg,
                        readCount(coreCounter),
                        Files.exists(packageCounter) ? readCount(packageCounter) : -1
                    ));
                }
            }
            return counts;
        }

        /**
         * Counts every throttling event of a core or a package once: the events of a core are the most that one of its
         * CPUs counted, and the events of a package are the most that one of its CPUs counted, whether the kernel
         * delivers the package events to every CPU of the package or to one of them.
         *
         * @return the throttling events between the two readings, or NaN without a previous reading or without counters
         */
        static double throttleEvents(Map<String, ThrottleCounts> previous, Map<String, ThrottleCounts> current) {
            if (previous == null || current.isEmpty()) {
                return Double.NaN;
            }
            Map<String, Long> coreEvents = new HashMap<>();
            Map<String, Long> packageEvents = new HashMap<>();
            current.forEach((cpu, counts) -> {
                ThrottleCounts before = previous.get(cpu);
                if (before != null) {
                    coreEvents.merge(counts.core(), Math.max(0, counts.coreCount() - before.coreCount()), Math::max);
                    if (counts.packageCount() >= 0 && before.packageCount() >= 0) {
                        packageEvents.merge(counts.pkg(), Math.max(0, counts.packageCount() - before.packageCount()), Math::max);
                    }
                }
            });
            return Stream.concat(coreEvents.values().stream(), packageEvents.values().stream())
                .mapToLong(Long::longValue)
                .sum();
        }

        private static long readCount(Path path) throws IOException {
            return Long.parseLong(Files.readString(path).trim());
        }

        private static List<Path> list(Path directory, String glob) throws IOException {
            List<Path> paths = new ArrayList<>();
            if (Files.isDirectory(directory)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, glob)) {
                    stream.forEach(paths::add);
                }
            }
            return paths;
        }

        private static double read(Path path) throws IOException {
            return Double.parseDouble(Files.readString(path).trim());
        }

        private static void closeQuietly(AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Nothing left to release
            }
        }

        @Override
        public void close() {
            executor.shutdownNow();
            ClockCounters counters = clockCounters;
            if (counters != null) {
                counters.close();
            }
        }
    }
}
