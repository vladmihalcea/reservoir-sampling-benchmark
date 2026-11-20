package com.vladmihalcea.phd.reservoir;

import com.vladmihalcea.phd.reservoir.workload.TraceWorkloads;
import com.vladmihalcea.phd.reservoir.workload.Workload;
import com.vladmihalcea.phd.reservoir.workload.WorkloadTrace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The committed traces load from the classpath: the captured connection-pool trace from its gzipped resource, with
 * every acquisition of the 60 s window, and the HTTP-latency stand-in from its plain CSV.
 *
 * @author Vlad Mihalcea
 */
public class TraceWorkloadsTest {

    @Test
    public void loadsTheCapturedConnectionPoolTraceAndTheHttpStandIn() {
        assertTrue(TraceWorkloads.available());

        WorkloadTrace pool = TraceWorkloads.connectionPoolTrace();
        assertEquals(1_397_392, pool.values().length);

        WorkloadTrace http = TraceWorkloads.httpLatencyTrace();
        assertEquals(50_000, http.values().length);

        List<Workload> workloads = TraceWorkloads.all();
        assertEquals("conn-pool-trace", workloads.get(0).name());
        assertEquals(1_000_000, workloads.get(0).generate(1_000_000, 1).length);
        assertEquals(50_000, workloads.get(1).generate(1_000_000, 1).length);
    }
}
