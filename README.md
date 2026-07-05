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
| Vitter Algorithm R | theory (uniform reservoir) | WRS-R |
| Algorithm L (skip-based) | theory (skip optimisation) | WRS-R |
| Efraimidis-Spirakis A-Res | theory (weighted, without replacement) | WRS-N |
| Efraimidis-Spirakis A-ExpJ | theory (exponential jumps) | WRS-N |
| Forward decay (parametric α) | theory (Cormode 2009) | WRS-N |
| WRS-B | theory (Hübschle-Schneider batched) | WRS-B |
| Dropwizard `UniformReservoir` | practice | WRS-R |
| Dropwizard `ExponentiallyDecayingReservoir` (α=0.015) | practice | WRS-N |
| Dropwizard `SlidingTimeWindowArrayReservoir` | practice | non-RS |
| Micrometer `DistributionSummary` | practice | non-RS (HdrHistogram) |
| Prometheus client classic `Summary` (CKMS) | practice | non-RS (rank summary) |
| Prometheus client native (exponential) histogram | practice | non-RS |
| Prometheus-style exponential histogram (clean-room baseline) | practice | non-RS |
| HdrHistogram | practice | non-RS |
| VictoriaMetrics `Summary` (valyala/histogram port) | practice | WRS-R |

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

## Quick start

```bash
mvn clean install                                   # build + fast unit tests
run-performance-benchmarks.bat                      # JMH families E1-E3, E5 -> metrics/jmh-results
run-accuracy-experiments.bat -Drs.reps=2 -Drs.maxStream=10000   # fast CSVs under metrics/
render-charts.bat                                   # reshape JMH results + gnuplot -> SVG + PDF
```

Full-scale runs (publication scale) drop the `-Drs.*` overrides and pass the publication JMH budget
`-Djmh.warmup=15 -Djmh.iters=30 -Djmh.secs=3` (the harness defaults to a lighter 5/10/2s pass for quick
iteration); see `CLAUDE.md` and `PLAN.md`.

The realistic traces under `core/src/main/resources/traces/` are model-generated stand-ins produced by
`TraceGenerator`; drop a trace captured from an instrumented app (same `timestamp_ns,value_ns` format) in
their place for the camera-ready run.

Charts land under `metrics/<Figure>/` as `.csv` + `.gp` + `.svg` + `.pdf`, one folder per figure;
`render-charts.bat` first runs `FiguresFromJmh` to reshape `metrics/jmh-results/` into the per-figure CSVs.
