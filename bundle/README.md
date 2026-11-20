# Reservoir bundle: the journal-extension campaign on the Hostner servers

This bundle runs the benchmarks and experiments of the reservoir-sampling journal extension (Part II of the ICASC
2026 survey: accuracy, forward-decay alpha, recency) and of the virtual-threads follow-up on the two rented Hostner
servers, s66 and s68 (Dell PowerEdge R240, Xeon E-2276G 6 cores/12 threads, 32 GB, Ubuntu 26.04). A server needs
only Java 25; Maven, Git, and Docker are not used. The IPs and passwords are in
`%USERPROFILE%\.dto2entity\hostner.properties`, outside every repository.

**Roles.** s66 is the database host and s68 the host that runs the benchmarks, the state the TOSEM role swap left
(s66 runs PostgreSQL 18.6 with the campaign settings; its WAL flush is 51 us against 147 us on s68, so the commit
path of the starvation block runs on the faster disk). The JMH and live blocks that need no database run on both
servers, with PostgreSQL stopped on s66, the second run being the replication on the twin machine.

`run-campaign.sh` refuses to start while another `java` process runs or while PostgreSQL runs on the same server, so
that nothing runs next to the benchmarks.

## Build (on the development machine)

```
mvn -P bundle -Dskip.unit.tests=true package
```

builds `starvation/target/reservoir-bundle.tar.gz` (about 30 MB): the classes of both modules, every dependency in
`lib/`, and the scripts of this folder with Unix line endings. Copy the same file to both servers and compare
`sha256sum` on both; every results folder records the SHA-256 of the jars it ran.

```
scp starvation/target/reservoir-bundle.tar.gz root@<server-ip>:/root/
ssh root@<server-ip> 'cd /root && tar -xzf reservoir-bundle.tar.gz'
```

## One-time setup (per server, outside any measurement window)

1. `bash reservoir-bundle/setup-jdk25.sh`: Temurin 25.0.3+9, the JDK of the ICASC runs, in `/opt/temurin-25`, after
   checking Adoptium's SHA-256. The system's default java (the Temurin 21 of the TOSEM bundles) is not changed.
2. The TOSEM `setup-server.sh` already set the `performance` governor, the C1 idle-state limit, the stopped
   background jobs, the firewall, and the clock; the server record of every block shows them.
3. On the database host only (s66), for the starvation block: `PEER_IP=<s68-ip> bash reservoir-bundle/setup-db.sh`
   creates the `uslpool` database and admits s68 for it in `pg_hba.conf`.

## Blocks

| Block | Tests | Answers | Database | Duration (estimate) |
|---|---|---|---|---|
| `smoke` | every new test at a tiny size, JMH plumbing | acceptance gate | no | ~15 min |
| `vt-jmh` | V6 `VirtualThreadRecorderBenchmarkTest`: 10 recorders and `none` x {platform, virtual} x {200, 1,000, 10,000} clients x {1, 10} ms of I/O, 10 forks of 5 x 3 s warm-up and 10 x 3 s measurement (the day-1 run of 13 Nov had the 8 recorders without OpenTelemetry; `otel-jmh` adds those cells) | cost of a shared recorder for thousands of requests; update tail latency; live accuracy; scrape cost | no | ~17 h |
| `vt-jmh-long` | V6, the same grid with 10 x 3 s warm-up and 20 x 3 s measurement per fork, 10 forks, and the end-to-end request time, the scrape percentiles, and the final-read delay in the side CSV | as `vt-jmh`, plus the recorder's cost in request latency and the scrapes that exceed Prometheus's 10 s timeout | no | ~37 h |
| `vt-update` | V3a `VirtualThreadUpdateExperimentTest` | update rate with up to 10,000 virtual writers that never block | no | ~5 min |
| `vt-live` | V7 `ConcurrentRecordingAccuracyExperimentTest`, 30 s x 5 repetitions at stall probabilities of 0.2%, 0.05%, 0.01%, and 0.001% | accuracy of p99, p99.9, max, and how often a recorder shows a timeout-level stall at all, under 200 to 10,000 concurrent writers | no | ~9 h |
| `accuracy` | E6, E6b, E7, E7b, E8 (full scale), E9 `TimeRecencyExperimentTest`, E10 `DropwizardAlphaExperimentTest` | RQ4 accuracy, RQ5 alpha, RQ6 recency | no | ~4 h (deterministic) |
| `starvation` | M1, V1, V3, V4 of the virtual-threads study: 5 interleaved trials of 15 s warm-up and 60 s measurement per configuration, 200 to 10,000 clients (V1 adds 2,000 and 5,000) | pool behavior and live recorder cost with a separate database host | s66 | ~13 h |
| `starvation-smoke` | V1, V3, V4 at a tiny size (1 trial of 2 s + 5 s, 200 and 2,000 clients in V1) | acceptance gate of `starvation` | s66 | ~15 min |
| `tail-replay` | V2 `AcquisitionTailAccuracyExperimentTest` over the V1 traces, the Dropwizard reservoirs 100 times each | which recorders hide the acquisition tail, and how often | no | ~30 min |
| `tail-replay-smoke` | V2 over the `starvation-smoke` traces, 3 replays | acceptance gate of `tail-replay` | no | ~1 min |
| `part1-jmh` | E1-E3, E5 at the ICASC publication budget (optional) | Part I's cost results on server hardware | no | ~10 h |
| `otel-jmh` | V6 for `none`, the two OpenTelemetry histograms, the Prometheus native histogram, and Micrometer; E1, E2, E5 (both) for the OpenTelemetry histograms with same-run references; 10 forks | what OpenTelemetry costs a request, an update, and a query | no | ~15 h |
| `otel-accuracy` | E6, E6b, E8, E9 with the OpenTelemetry histograms in the catalog | RQ4 and RQ6 for OpenTelemetry | no | several hours (deterministic) |

The journal's JMH blocks run `JMH_FORKS` forks (10 by default, as the TOSEM campaign), so every reported JMH block
shares one budget; `part1-jmh` keeps the ICASC paper's 2 forks (`PART1_FORKS`), since it reruns that paper's
results. The accuracy experiments (E6 to E10) drive a logical clock and seeded streams, so their results do not
depend on the machine; run them once. Only the seeded strategies (the reference algorithms and VictoriaMetrics)
repeat bit for bit: Dropwizard's reservoirs draw from `ThreadLocalRandom`, so their rows vary between runs within the
reported confidence intervals. A strategy's seed is derived from the stream's (`StrategyCatalog.strategySeed`); the
blocks before 17 Nov passed both the same seed, which biased A-Res, A-ExpJ, and WRS-B, so the `accuracy` block of
15 Nov is superseded.

## Telemetry

The same telemetry as the TOSEM campaign:

- Every JMH fork attaches the TOSEM `TelemetryProfiler`, which reports per iteration the CPU clock (APERF/MPERF),
  utilization, performance limit, temperature, thermal throttles, and package power and its limit of the host that
  runs the forks (`app.*`), and of the database host (`db.*`) when its agent runs. V6 also attaches the
  `RecordingProfiler`, the counterpart of the TOSEM layer profiler: the recorder's mean update latency, the mean
  response time, the scrape time, and the process CPU load per iteration (`rec.*`; the update and response
  percentiles of each fork are in V6's side CSV), and JMH's GC profiler (`gc.*`). All of them are secondary results in the JMH JSON
  and columns of the tidy CSV.
- Every block, JMH or not, writes `telemetry.csv`: the same CPU metrics every 10 s for the whole block, for the
  benchmark host and, in the starvation block, for the database host, so the live and accuracy experiments have them
  too; a test's start and end times in `status.csv` place it in the series.
- On the database host, `start-agent.sh <name>` runs the telemetry agent (port 9500, nice 19) as the service
  `rs-agent`; the firewall of the TOSEM `setup-server.sh` already admits 9500 from the peer.

## Order

Start every campaign with `start-campaign.sh`, which runs it as the transient service `rs-campaign` (an SSH
disconnect does not stop it) and takes the postgres password on stdin into a root-only environment file:

```
bash reservoir-bundle/start-campaign.sh <name> "<blocks>" [<database host IP>] < password-line
```

First the blocks without a database, on both servers in parallel, with PostgreSQL stopped on s66
(`systemctl stop postgresql`; the TOSEM databases stay on disk):

```
# s68 (benchmark host)                              # s66 (database host, PostgreSQL stopped)
start-campaign.sh day1 "smoke vt-jmh vt-update vt-live"   start-campaign.sh day1 "smoke vt-jmh accuracy"
```

s66's `vt-jmh` replicates s68's on the twin machine: the two must agree within their confidence intervals before
either is reported (report s68's, cite s66's as the replication).

Then the starvation block, with PostgreSQL and the telemetry agent on s66 (`systemctl start postgresql` and
`start-agent.sh starvation` there):

```
# s68
start-campaign.sh starvation "starvation tail-replay" <s66-ip> < password-line
```

`tail-replay` reads the traces of the newest `starvation` block on the same host, unless `TRACES` names another
`metrics/VirtualThreads/traces` folder.

The password is read from `JDBC_PASSWORD` by the harness, so it never appears on a command line or in
`pidstat.log`. A configured database that does not answer stops the block; it never falls back to Docker.

### After day 1: OpenTelemetry

The bundle built after day 1 started (13 Nov) adds the OpenTelemetry SDK's two histograms to the catalog, and the
Prometheus client's summary and native histogram to the concurrent recorders of V3a, V7, and the starvation study.
Deploy it only when no block runs (the forks load the jars of `/root/rs/reservoir-bundle`), then:

1. `starvation` and `tail-replay` as above, with the new bundle, so they include the new recorders.
2. `otel-jmh` on s68, and on s66 as its replication (PostgreSQL stopped there), in parallel.
3. `otel-accuracy vt-update vt-live` on one server: the deterministic experiments again with the OpenTelemetry
   histograms, and V3a and V7 with the full concurrent recorder set (V7 takes about two hours with ten recorders).

### After the 17 Nov review: longer, repeated, and probabilistic

The review of the day-1 and day-2 results found single 10 s windows in the starvation block (33 identical V3 trials
ranged from 2,001 to 12,184 timeouts), a stall rate in V7 at which every reservoir shows a stall, one replay per trace
in V2 for the unseeded Dropwizard reservoirs, and a V6 response time that cannot show a slow `update`. The bundle of
17 Nov changes those tests (and E6, E6b, E7, E10, see the Javadoc of each) and reruns:

1. `starvation-smoke tail-replay-smoke` on s68 against s66 (PostgreSQL and `start-agent.sh` on s66), then
   `starvation tail-replay vt-live vt-jmh-long` on s68.
2. When `starvation` has ended on s68: stop PostgreSQL and `rs-agent` on s66, then `smoke accuracy vt-jmh-long` on
   s66, the replication of s68's `vt-jmh-long` on the twin machine.
3. **Still to run (18 Nov): V1 again.** In the `starvation` block of 17 Nov on s68, `VirtualThreadPoolExperimentTest`
   ran out of its 8 GB heap after 3.6 h, in trial 2 of 5 (HikariCP at its optimum size). Its summaries kept every
   window's acquisition trace: 400 windows of 60 s, 20 to 100 MB each. The other three tests (M1, V3, V4) passed.
   `v1-trials.csv` has trials 0 and 1 and part of trial 2, and `v1-pool-behavior.csv` is missing. The trial-0 traces,
   which `tail-replay` reads, are complete. The fix (`Result.withoutTrace()`, called by `TrialSummaries.add`,
   covered by `StarvationDriverTest`) is in the bundle built on 20 Nov (sha256 `3b5bd20a...`), which also adds the
   block `starvation-v1`: V1 alone with the `starvation` block's settings, about 8.6 h. Its traces land in a
   `starvation-v1-<host>-*` folder, so `tail-replay` keeps reading the complete trial-0 traces of 17 Nov. Steps 1 and 2
   ended on 20 Nov (both servers idle at 07:20 local time) and their results are fetched, so the bundle can be
   deployed. Then run it before the USL campaign takes the servers: on s66, `systemctl start postgresql` and
   `start-agent.sh starvation`; on s68, `start-campaign.sh starvation-v1 "starvation-v1" <s66-ip> < password-line`.
4. **Still to run (20 Nov): E6 again.** In the `accuracy` block of 17 Nov on s66, `QuantileAccuracyExperimentTest` failed
   with `ArrayIndexOutOfBoundsException: Index 50000 out of bounds for length 50000` at its first captured trace. A trace
   holds 50,000 values, and the 17 Nov version sized its timestamps by the requested stream (1,000,000). The six other
   tests passed. The earlier version iterated the values it got, so its trace rows labeled 1,000,000 are streams of
   50,000; use the rows of the rerun. The fix (a trace's stream is at most its own length, and its row carries that
   length) is in the same bundle, with the block `accuracy-e6`, about 10 minutes, PostgreSQL stopped: on s66, after
   V1, `start-campaign.sh accuracy-e6 "accuracy-e6"`.
5. **20 Nov, after the review of V1 and E6: a captured trace and a longer E7.** Steps 3 and 4 passed (V1 5 of 5 trials,
   06:15 to 14:45 UTC; E6 05:59 to 06:05 UTC). The review left two gaps. First, E6's connection-pool trace was a model
   stand-in. It is now the captured trace that V2 replays (HikariCP, 10 connections, 1,000 platform-thread clients,
   trial 0 of 17 Nov, 1,397,392 acquisitions), committed as `core/src/main/resources/traces/conn-pool-trace.csv.gz`
   (see `TraceWorkloads`); the HTTP-latency trace stays the stand-in. Second, E7's 30 repetitions gave intervals too
   wide to separate alpha 0.015 from the best alpha on three of its four workloads. The block `accuracy-e7` runs it
   with 300; the seeds are `3000 + rep`, so the first 30 are those of 17 Nov. Both blocks are deterministic and need
   no database, so they run on s68 (PostgreSQL is stopped there) while s66 is set up for the USL campaign:
   `start-campaign.sh accuracy-e6e7 "accuracy-e6 accuracy-e7"`, about one hour.

The blocks of 13 to 17 Nov stay in `metrics/Hostner-2026-11/`; the new folders supersede their `starvation`,
`tail-replay`, `vt-live`, and `accuracy` results, and `vt-jmh-long` supersedes `vt-jmh` and the V6 cells of
`otel-jmh` (E1, E2, and E5 of `otel-jmh` stay).

## Results

Each block writes `results/<block>-<host>-<UTC time>/`:

- `metrics/`: the CSVs of every test, in the repository's `metrics/<Figure>/` layout, and `metrics/jmh-results/`
  (JMH JSON and CSV);
- `console.log` and `status.csv` (one row per test with its exit code; 1 means failed or skipped by an assumption);
- `server-record-start.txt` and `server-record-end.txt` (governor, idle states, throttling counters, limits, Java,
  running services, busiest processes); a block whose two records differ in anything but time and counters needs
  a look before its results are used;
- `pidstat.log` (CPU per process every 10 s), `telemetry.csv` (the CPU telemetry every 10 s, see Telemetry), and
  `bundle.sha256`.

Copy the folders back into a new folder of the repository, for example `metrics/Hostner-2026-11/`, so that the
committed laptop results are not overwritten:

```
scp -r root@<server-ip>:/root/results ./metrics/Hostner-2026-11/
```

## The new experiments

- **V6, `VirtualThreadRecorderBenchmark` (JMH).** Clients block for 1 or 10 ms in `Thread.sleep` (a database or HTTP
  call; a virtual thread unmounts), do about 1-2 us of CPU work, and record their response time into one shared
  recorder: the four Dropwizard reservoirs, Micrometer's summary, the Prometheus client's summary and native
  histogram, and HdrHistogram's `Recorder`. The score is requests per second, against the `none` recorder of the
  same cell. A side CSV (`metrics/VirtualThreadRecording/vt-recording.csv`, one row per fork) adds the `update`
  latency percentiles up to p99.99, the exact response-time percentiles of the measurement and of the whole fork (the
  span the recorder saw) next to what the recorder reports, the scrape duration (a reader every 10 s) and the range
  of the p99 it read, the CPU load, and the retained samples; the per-iteration values are the `rec.*` results. The
  forks run with a 16 GB heap (`rs.vt.heap`, 8 GB before 17 Nov), which holds the sliding window's samples over the
  90 s forks of `vt-jmh-long`. Since 17 Nov the side CSV also has the end-to-end time of the requests (until the
  `update` returns, which the recorded response time cannot include), the scrape p50 and p99 and the number of scrapes
  over 10 s (Prometheus's default `scrape_timeout`), the p99 of the last scrape, and the delay of the final read, which
  waits for a scrape in progress (Micrometer's percentiles read 0 after 41 to 59 s without a record,
  `MicrometerWindowTest`).
- **V7, `ConcurrentRecordingAccuracyExperimentTest`.** 200 to 10,000 platform or virtual writers record a seeded
  stream with stalls of 0.5-5 s; the streams are regenerated afterwards for the truth (a five-digit HdrHistogram, finer
  than every recorder), so nothing but the recorder runs during the window. It also reports whether a stall shows in
  the reported max and the retained sample count of the Dropwizard reservoirs. Since 17 Nov it sweeps the stall
  probability (0.2%, where every reservoir keeps about two stalls, down to 0.001%) and reports, next to the share of
  the repetitions that showed a stall, the probability that a uniform reservoir of 1,028 samples keeps one.
- **E9, `TimeRecencyExperimentTest`.** Recency in seconds of logical time at 100, 1,000, and 10,000 requests per
  second: an incident that starts, one that ends, a 30 s spike, and a spike followed by night traffic (1 per
  second). Every recorder follows the same clock, Micrometer included (`StrategyCatalog.createOnClock`). It reports
  the time to converge and to settle, the time to cross a 2x-baseline alert, and the peak shown for a spike.
- **OpenTelemetry (`OpenTelemetryHistogram`).** The SDK (`opentelemetry-sdk-metrics` 1.65.0) records in seconds
  into a `DoubleHistogram`, read by a pull reader with cumulative temporality, the OTLP default, in two aggregations:
  the explicit buckets the HTTP semantic conventions advise for `http.server.request.duration` (5 ms to 10 s),
  which the OpenTelemetry Java agent records for every request, and the base-2 exponential histogram with the SDK
  defaults (160 buckets, maximum scale 20). The SDK computes no quantiles, so the adapter estimates them as
  Prometheus' `histogram_quantile` does, linearly within the bucket; OpenTelemetry also records the exact minimum and
  maximum, which `q = 0` and `q = 1` return. With millisecond latencies, about 70% of the values fall in the explicit
  histogram's first bucket (0-5 ms), so its p50 is ~19% off by design; `OpenTelemetryHistogramTest` checks it
  against the exact bucket counts instead of the accuracy gate.
- **E10, `DropwizardAlphaExperimentTest`.** Alpha = 0.015 on the library itself (locking and lock-free): the age of
  what the snapshot reports against the Javadoc's "past 5 minutes", the Javadoc's "99.9% confidence level with a 5%
  margin of error", the alpha trade-off between steady-state error and adaptation, and the hourly rescale, where
  `exp(alpha * t)` overflows a double for alpha above 0.197.
