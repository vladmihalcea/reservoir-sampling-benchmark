# reservoir-sampling-benchmark

Benchmarks comparing the reservoir sampling / quantile-estimation strategies used by production metrics
frameworks against the academic literature, supporting the ICASC 2026 survey **"A Survey of Reservoir
Sampling Techniques in Production Metrics Frameworks for Advanced Computing Infrastructures: Theory vs.
Practice"** (PhD Pillar 1 — Quantifying Performance).

Application Performance Monitoring systems must estimate tail quantiles (p95/p99/p999) of latency over
unbounded streams in bounded memory. This project audits how four widely deployed frameworks actually do
that and measures accuracy, memory and throughput against reference algorithms from the literature.

## What is compared

| Strategy | Origin | Hübschle-Schneider class |
|----------|--------|--------------------------|
| Vitter Algorithm R | theory (uniform reservoir) | WRS-N (unweighted) |
| Algorithm L (skip-based) | theory (skip optimization) | WRS-N (unweighted) |
| Efraimidis-Spirakis A-Res | theory (weighted, without replacement) | WRS-N |
| Efraimidis-Spirakis A-ExpJ | theory (exponential jumps) | WRS-N |
| Forward decay (parametric α) | theory (Cormode 2009) | WRS-N |
| WRS-B | theory (Hübschle-Schneider batched) | WRS-B |
| Dropwizard `UniformReservoir` | practice | WRS-N (unweighted) |
| Dropwizard `ExponentiallyDecayingReservoir` (α=0.015) | practice | WRS-N |
| Dropwizard `SlidingTimeWindowArrayReservoir` | practice | non-RS |
| Micrometer `DistributionSummary` | practice | non-RS (HdrHistogram) |
| Prometheus client classic `Summary` (CKMS) | practice | non-RS (rank summary) |
| Prometheus client native (exponential) histogram | practice | non-RS |
| Prometheus-style exponential histogram (clean-room baseline) | practice | non-RS |
| HdrHistogram | practice | non-RS |
| VictoriaMetrics `Summary` (valyala/histogram port) | practice | WRS-N (unweighted) |

## Experiments

- **E1 update throughput** — per-`update()` cost by strategy and reservoir size *k* (JMH).
- **E2 concurrency** — shared-reservoir throughput at 1–16 threads; single-writer reservoirs run under one
  external lock, internally thread-safe frameworks run unguarded, so lock contention shows against
  lock-free scaling (JMH).
- **E3 batched insertion** — per-element Efraimidis-Spirakis and Dropwizard ExpDecay vs. batched WRS-B
  across batch sizes 10³–10⁶ (the order-of-magnitude speedup claim) (JMH).
- **E4 memory** — retained footprint by strategy and *k* (JOL) + per-update allocation rate
  (`gc.alloc.rate.norm`, enabled with `-Djmh.profGc=true`).
- **E5 query cost** — cost of reading p99, idle and under concurrent updates (grouped reader/writer) (JMH).
- **E6 accuracy** — estimated vs. exact quantiles over 8 synthetic workloads plus captured traces, 30
  seeds, with 95% CIs.
- **E6b samples-for-accuracy** — smallest reservoir size *k* each strategy needs to reach a target p99
  error (the memory-for-accuracy headline).
- **E7 forward-decay α sensitivity** — tail-error vs. α, exposing the unvalidated hardcoded 0.015.
- **E7b α convergence** — post-shift p99 convergence speed as a function of α.
- **E8 recency** — p99 convergence speed after a regime shift.

### Follow-up: pool starvation under virtual threads (`starvation` module)

The `starvation` module applies the survey's cost and accuracy questions to a real tail-heavy workload:
HikariCP, DBCP2, and Agroal on PostgreSQL with 200, 1,000, or 10,000 platform or virtual threads competing
for a connection. Unlike `core`, it needs a database (a local `uslpool` PostgreSQL database, or Docker). Its
live recorders are the `core` adapters listed in `StrategyCatalog.CONCURRENT_RECORDERS`, so the live
experiments and the offline replay score the same code.

- **M1 driver validation** — the virtual-thread driver reproduces a same-session JMH run within a few
  percent.
- **V1 pool behavior** — throughput, exact acquisition percentiles, timeouts, and Jain's fairness by pool,
  thread kind, and client count; exports lossless acquisition traces (`metrics/VirtualThreads/traces/`,
  git-ignored).
- **V2 tail accuracy** (in `core`) — the production recorders replayed over the V1 traces, scored on p99.9,
  the maximum, and whether a timeout-level wait shows at all.
- **V3 recorder overhead** — live, at 10,000 virtual threads; **V3a** (in `core`) — recorder update rate with
  up to 10,000 virtual writers, no database.
- **V4 admission control** — none, fair or unfair semaphore, bounded executor, and rate limiter, comparing
  the pool's acquisition time with the clients' total wait.

## Quick start

```bash
mvn clean install                                   # build + fast unit tests
run-performance-benchmarks.bat                      # JMH families E1-E3, E5 -> metrics/jmh-results
run-accuracy-experiments.bat -Drs.reps=2 -Drs.maxStream=10000   # fast CSVs under metrics/
render-charts.bat                                   # reshape JMH results + gnuplot -> SVG + PDF
mvn -P benchmark-tests -pl starvation -Dit.test=VirtualThreadPoolExperimentTest verify   # needs PostgreSQL
```

Full-scale runs (publication scale) drop the `-Drs.*` overrides and pass the publication JMH budget
`-Djmh.warmup=15 -Djmh.iters=30 -Djmh.secs=3` (the harness defaults to a lighter 5/10/2s pass for quick
iteration); see `CLAUDE.md` and `PLAN.md`.

The realistic traces under `core/src/main/resources/traces/` are model-generated stand-ins produced by
`TraceGenerator`; drop a trace captured from an instrumented app (same `timestamp_ns,value_ns` format) in
their place for the camera-ready run.

Charts land under `metrics/<Figure>/` as `.csv` + `.gp` + `.svg` + `.pdf`, one folder per figure;
`render-charts.bat` first runs `FiguresFromJmh` to reshape `metrics/jmh-results/` into the per-figure CSVs.

## Result capture, aggregation, and visualization

Each JMH run writes a structured JSON and CSV file per benchmark class under `metrics/jmh-results/`,
recording environment metadata, per-iteration timings, the per-benchmark mean, and the 99.9% confidence
interval. When the GC profiler is enabled (`-Djmh.profGc=true`), the normalized allocation rate
(`gc.alloc.rate.norm`, bytes per `update()`) is captured in the same records. `FiguresFromJmh` then
reshapes these long-format results into one chart-ready wide CSV per figure under `metrics/<Figure>/`,
beside a sibling gnuplot script. The accuracy, memory, and recency experiments write their own long-format
CSVs (strategy, quantile, stream length, mean relative error, 95% CI) and matching wide CSVs into the same
layout. `render-charts.bat` renders every `metrics/**/*.gp` to two formats side by side: SVG for slides and
PDF for the camera-ready article.

**Reporting conventions.** Time is reported in nanoseconds or milliseconds per operation with the 99.9%
confidence-interval half-width as the dispersion indicator for the JMH measurements; retained memory in
bytes; accuracy as mean relative error ± 95% CI over 30 seeds.

### Build layout and test gating

Heavy benchmarks and experiments are gated behind the `benchmark-tests` Maven profile and a
`*BenchmarkTest` / `*ExperimentTest` / `*ReportTest` naming scheme, so the fast `mvn install` build (unit
tests via surefire) stays free of them; failsafe runs them under the profile:
`mvn -P benchmark-tests -Dit.test=<Name> integration-test`. JMH timing parameters are held identical across
every strategy — the publication budget is 15 warm-up + 30 measurement iterations of 3 s each, 2 forks,
99.9% CI; the harness defaults to a lighter 5/10/2 s pass for quick iteration.
