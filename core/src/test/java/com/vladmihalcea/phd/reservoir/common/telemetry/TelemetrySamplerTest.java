package com.vladmihalcea.phd.reservoir.common.telemetry;

import com.vladmihalcea.phd.reservoir.common.telemetry.TelemetrySampler.LinuxSampler;
import com.vladmihalcea.phd.reservoir.common.telemetry.TelemetrySampler.LinuxSampler.PowerZone;
import com.vladmihalcea.phd.reservoir.common.telemetry.TelemetrySampler.LinuxSampler.ThrottleCounts;
import com.vladmihalcea.phd.reservoir.common.telemetry.TelemetrySampler.Metric;
import com.vladmihalcea.phd.reservoir.common.telemetry.TelemetrySampler.WindowsSampler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmh.results.AggregationPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the Windows sampler maps every typeperf value to its counter,
 * also when typeperf leaves a counter out of its CSV header, and that the Linux sampler
 * counts every thermal throttling event of a core or a package once, measures the clock
 * of the running cores, and the package power.
 *
 * @author Vlad Mihalcea
 */
public class TelemetrySamplerTest {

    /**
     * One second of the Xeon E-2276G (base 3,800 MHz) with one CPU busy at 4,889 MHz and one idle CPU,
     * whose counters barely move, as measured on the server: the clock is the busy CPU's, not the mean of both.
     */
    @Test
    public void testBusyClockWeighsEveryCpuByItsRunningTime() {
        // APERF of cpu0 and cpu1, then MPERF of cpu0 and cpu1
        long[] before = {1_000_000_000L, 5_000_000L, 2_000_000_000L, 7_000_000L};
        long[] after = {1_000_000_000L + 4_851_000_000L, 5_000_000L + 4_800L, 2_000_000_000L + 3_770_600_000L, 7_000_000L + 3_800L};

        double clock = LinuxSampler.busyClock(before, after, 3800);

        assertEquals(3800.0 * (4_851_000_000L + 4_800) / (3_770_600_000L + 3_800), clock, 1e-9);
        assertEquals(4889, clock, 1);
        assertTrue(Double.isNaN(LinuxSampler.busyClock(null, after, 3800)), "the first sample has no previous reading");
        assertTrue(Double.isNaN(LinuxSampler.busyClock(after, after, 3800)), "no CPU ran");
    }

    /**
     * The package zone counts, its core and dram subzones do not, and a counter that wrapped around
     * at its maximum range still gives the energy since the previous reading.
     */
    @Test
    public void testPackagePower() throws IOException {
        zone("intel-rapl-0", "package-0", 70_707_937_090L);
        zone("intel-rapl-0-0", "core", 16_677_704_924L);
        zone("intel-rapl-0-1", "dram", 82_082_495_068L);
        List<PowerZone> zones = LinuxSampler.packageZones(cpuDirectory, ZONES);
        assertEquals(1, zones.size());
        assertEquals(80, LinuxSampler.powerLimit(zones), 1e-9);
        assertEquals(List.of(70_707_937_090L), Arrays.stream(LinuxSampler.energies(zones)).boxed().toList());

        // 25 J in half a second
        assertEquals(50, LinuxSampler.power(zones, new long[]{1_000_000_000L}, new long[]{1_025_000_000L}, 500_000_000L), 1e-9);
        // The counter wrapped around: 4 J before the wrap and 6 J after it, in one second
        assertEquals(10, LinuxSampler.power(zones, new long[]{MAX_ENERGY_RANGE - 4_000_000L}, new long[]{6_000_000L}, 1_000_000_000L), 1e-9);
        assertTrue(Double.isNaN(LinuxSampler.power(zones, null, new long[]{6_000_000L}, 1_000_000_000L)));
    }

    /**
     * Without RAPL, e.g., in a virtual machine, or without root, the power is missing.
     */
    @Test
    public void testNoPowerZones() {
        assertTrue(LinuxSampler.packageZones(cpuDirectory.resolve("missing"), ZONES).isEmpty());
    }

    private static final long MAX_ENERGY_RANGE = 262_143_328_850L;

    /** The zone names of Linux contain colons, which Windows does not allow in file names. */
    private static final String ZONES = "intel-rapl-*";

    private void zone(String name, String zoneName, long energy) throws IOException {
        Path zone = Files.createDirectories(cpuDirectory.resolve(name));
        Files.writeString(zone.resolve("name"), zoneName + "\n");
        Files.writeString(zone.resolve("energy_uj"), energy + "\n");
        Files.writeString(zone.resolve("max_energy_range_uj"), MAX_ENERGY_RANGE + "\n");
        // The long-term limit of the Xeon E-2276G servers; the subzones report 0
        Files.writeString(zone.resolve("constraint_0_power_limit_uw"), (zoneName.startsWith("package") ? 80_000_000 : 0) + "\n");
    }

    /**
     * A row of the XPS 15 with three thermal zones, of which only the first one has a sensor,
     * recorded when the header lacked the {@code % Processor Time} counter.
     */
    private static final String ROW = "\"10/30/2026 11:27:16.105\",\"69.965508\",\"4.410312\",\"100.000000\"," +
        "\"100.000000\",\"100.000000\",\"100.000000\",\"298.000000\",\"0.000000\",\"0.000000\"";

    private final List<WindowsSampler.Counter> counters = WindowsSampler.counters(2600);

    @TempDir
    Path cpuDirectory;

    @Test
    public void testColumnsFollowTheCounterOrder() {
        String[] cells = WindowsSampler.cells(ROW);
        DoubleSummaryStatistics[] statistics = WindowsSampler.sample(WindowsSampler.columns(counters, cells.length), cells);

        assertEquals(69.965508 * 26, statistics[Metric.CPU_MHZ.ordinal()].getAverage(), 1e-6);
        assertEquals(4.410312, statistics[Metric.CPU_UTILIZATION.ordinal()].getAverage(), 1e-6);
        assertEquals(100, statistics[Metric.CPU_PERFORMANCE_LIMIT.ordinal()].getMin(), 1e-6);
        assertEquals(3, statistics[Metric.THERMAL_LIMIT.ordinal()].getCount());
        assertEquals(100, statistics[Metric.THERMAL_LIMIT.ordinal()].getMin(), 1e-6);
        assertEquals(1, statistics[Metric.TEMPERATURE.ordinal()].getCount());
        assertEquals(24.85, statistics[Metric.TEMPERATURE.ordinal()].getMax(), 1e-6);
        // Windows reports the limits instead of the throttling events
        assertEquals(0, statistics[Metric.THERMAL_THROTTLES.ordinal()].getCount());
    }

    @Test
    public void testRowWidthThatDoesNotFitTheCounters() {
        // Three single counters and five more cells cannot be split evenly between the two wildcard counters
        assertTrue(Arrays.stream(WindowsSampler.columns(counters, 9)).allMatch(Objects::isNull));
    }

    @Test
    public void testEveryAggregationIsAJmhAggregationPolicy() {
        for (Metric metric : Metric.values()) {
            assertDoesNotThrow(() -> AggregationPolicy.valueOf(metric.aggregation().name()), metric.label());
        }
    }

    /**
     * Two cores of one package, the first with two hyperthreads that both count its events,
     * and package events that only the CPUs of the first core counted.
     */
    @Test
    public void testThrottleEventsCountEveryCoreAndPackageOnce() throws IOException {
        cpu("cpu0", 0, 0, 3, 10L);
        cpu("cpu1", 0, 0, 3, 10L);
        cpu("cpu2", 0, 1, 0, 10L);
        Map<String, ThrottleCounts> before = LinuxSampler.throttleCounts(cpuDirectory);
        assertEquals(3, before.size());
        assertTrue(Double.isNaN(LinuxSampler.throttleEvents(null, before)), "the first sample has no previous reading");

        cpu("cpu0", 0, 0, 5, 12L);
        cpu("cpu1", 0, 0, 5, 12L);
        cpu("cpu2", 0, 1, 1, 10L);
        Map<String, ThrottleCounts> after = LinuxSampler.throttleCounts(cpuDirectory);

        // Core 0/0: 2 events, core 0/1: 1 event, package 0: 2 events
        assertEquals(5, LinuxSampler.throttleEvents(before, after), 0);
        assertEquals(0, LinuxSampler.throttleEvents(after, after), 0);
    }

    /**
     * Without the package thermal status, the CPUs have no package counter, and the core events still count.
     */
    @Test
    public void testThrottleEventsWithoutPackageCounters() throws IOException {
        cpu("cpu0", 0, 0, 7, null);
        Map<String, ThrottleCounts> before = LinuxSampler.throttleCounts(cpuDirectory);
        cpu("cpu0", 0, 0, 8, null);
        assertEquals(1, LinuxSampler.throttleEvents(before, LinuxSampler.throttleCounts(cpuDirectory)), 0);
    }

    /**
     * AMD CPUs and most virtual machines have no throttling counters, so the metric is missing, not zero.
     */
    @Test
    public void testNoThrottleEventsWithoutCounters() throws IOException {
        Files.createDirectories(cpuDirectory.resolve("cpu0").resolve("topology"));
        Map<String, ThrottleCounts> counts = LinuxSampler.throttleCounts(cpuDirectory);
        assertTrue(counts.isEmpty());
        assertTrue(Double.isNaN(LinuxSampler.throttleEvents(counts, counts)));
    }

    private void cpu(String name, int pkg, int core, long coreCount, Long packageCount) throws IOException {
        Path cpu = cpuDirectory.resolve(name);
        Path topology = Files.createDirectories(cpu.resolve("topology"));
        Path throttle = Files.createDirectories(cpu.resolve("thermal_throttle"));
        Files.writeString(topology.resolve("physical_package_id"), pkg + "\n");
        Files.writeString(topology.resolve("core_id"), core + "\n");
        Files.writeString(throttle.resolve("core_throttle_count"), coreCount + "\n");
        if (packageCount != null) {
            Files.writeString(throttle.resolve("package_throttle_count"), packageCount + "\n");
        }
    }
}
