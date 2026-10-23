#!/usr/bin/env bash
# Runs blocks of the journal-extension campaign on a Hostner server, one results folder per block:
#
#   bash run-campaign.sh <block> [<block> ...]
#
# Blocks (README.md has the order, the servers, and the durations):
#   smoke        every new test at a tiny size, plus the JMH plumbing (about 10 minutes); the acceptance gate
#   vt-jmh       V6  VirtualThreadRecorderBenchmarkTest, the full grid, 10 forks of 5 x 3 s + 10 x 3 s (about 17 hours)
#   vt-jmh-long  V6  the full grid, 10 forks of 10 x 3 s warm-up + 20 x 3 s measurement (about 37 hours)
#   vt-update    V3a VirtualThreadUpdateExperimentTest, 5 repetitions (about 5 minutes)
#   vt-live      V7  ConcurrentRecordingAccuracyExperimentTest, 30 s x 5 repetitions at four stall probabilities
#                (about 9 hours)
#   accuracy     E6, E6b, E7, E7b, E8 at full scale, E9 TimeRecency, E10 DropwizardAlpha (deterministic, hours)
#   part1-jmh    optional: E1-E3 and E5 of the ICASC paper at its publication budget, on server hardware (long)
#   starvation   M1, V1, V3, V4 against the database server, 5 trials of 15 s + 60 s per configuration (about
#                13 hours): needs DB_HOST and JDBC_PASSWORD
#   starvation-smoke  V1, V3, V4 at a tiny size against the database server (about 15 minutes), the gate before
#                starvation
#   starvation-v1  V1 alone, as in the starvation block (about 8.6 hours): the rerun of 18 Nov, after V1 ran out of
#                heap in the 17 Nov block; needs DB_HOST and JDBC_PASSWORD
#   accuracy-e6  E6 QuantileAccuracyExperimentTest alone, as in the accuracy block (about 10 minutes): the rerun of 20 Nov,
#                after E6 ran past the end of the 50,000-value traces in the 17 Nov block
#   tail-replay  V2 over the V1 traces: TRACES (a starvation results folder's metrics/VirtualThreads/traces), by default
#                those of the newest starvation block on this host, so "starvation tail-replay" runs as one campaign
#   tail-replay-smoke  V2 over the traces of the newest starvation-smoke block on this host, 3 replays
#   otel-jmh     OpenTelemetry at the journal budget: V6's OpenTelemetry cells and E1, E2, E5 (cost of update and
#                query), each with same-run references (about 15 hours)
#   otel-accuracy  E6, E6b, E8, E9 again with the OpenTelemetry histograms in the catalog (deterministic, hours)
#
# Environment: JMH_FORKS (10, as the TOSEM campaign) is the fork count of every journal JMH block, so all reported
# blocks share it (part1-jmh keeps the ICASC 2 forks, PART1_FORKS); TELEMETRY_AGENT_URL (start-campaign.sh sets it
# to http://$DB_HOST:9500) adds the database host's telemetry to the JMH results and to telemetry.csv; FORCE=1 starts
# even if another java process runs; ALLOW_LOCAL_POSTGRES=1 starts even if PostgreSQL runs on this server.
# Results go to ./results/<block>-<host>-<UTC time>/ (metrics/, console.log, status.csv, pidstat.log, telemetry.csv,
# the server record at the start and the end, and the SHA-256 of the bundle's jars).
set -uo pipefail

BUNDLE="$(cd "$(dirname "$0")" && pwd)"
HOST="$(hostname -s)"
FORKS="${JMH_FORKS:-10}"
VT_BUDGET="-Djmh.forks=$FORKS -Djmh.warmup=5 -Djmh.iters=10 -Djmh.secs=3"
# The longer V6 budget: twice the warm-up, so the throughput of the 10,000-client cells settles before it is measured
# (it drifted 5-14% within the November forks), and twice the measurement, so each fork collects more scrapes.
VT_LONG_BUDGET="-Djmh.forks=$FORKS -Djmh.warmup=10 -Djmh.iters=20 -Djmh.secs=3"
# The tiny sizes of the starvation smoke: one trial of 2 s + 5 s, two client counts.
STARVATION_SMOKE="-Dusl.vt.trials=1 -Dusl.vt.reps=1 -Dusl.vt.clients=200,2000 -Dusl.vt.warmupMillis=2000 -Dusl.vt.measureMillis=5000"
# Part I keeps the published ICASC budget (2 forks, 15 + 30 iterations of 3 s), so its rerun on server hardware is
# comparable with the conference paper; it is a separate publication from the journal blocks.
PART1_BUDGET="-Djmh.forks=${PART1_FORKS:-2} -Djmh.warmup=15 -Djmh.iters=30 -Djmh.secs=3"
# The OpenTelemetry cells, with the references measured in the same run (results of separate sessions are not
# comparable at the few-percent level): the Prometheus native histogram (OpenTelemetry's exponential histogram is its
# counterpart), Micrometer (Spring's default), and, for the cost benchmarks, the clean-room exponential histogram,
# HdrHistogram, and Dropwizard's decaying reservoir.
OTEL="OpenTelemetry-Explicit,OpenTelemetry-Exponential"
OTEL_V6="none,$OTEL,Prometheus-Native,Micrometer-Summary"
OTEL_COST="$OTEL,Prometheus-Native,Prometheus-Histogram,HdrHistogram,Micrometer-Summary,Dropwizard-ExpDecay"
OTEL_CONCURRENT="$OTEL,Prometheus-Native,Micrometer-Summary,Dropwizard-ExpDecay"

if [ $# -lt 1 ]; then
    sed -n '2,31p' "$0"
    exit 2
fi

# ---------------------------------------------------------------- guards (measurement non-interference)
if [ "${FORCE:-0}" != "1" ] && pgrep -x java > /dev/null; then
    echo "Another java process runs on $HOST (a TOSEM block?):" >&2
    ps -o pid,etime,args -C java >&2
    echo "Wait until it ends, or set FORCE=1." >&2
    exit 3
fi
if [ "${ALLOW_LOCAL_POSTGRES:-0}" != "1" ] && systemctl is-active --quiet postgresql 2>/dev/null; then
    echo "PostgreSQL runs on $HOST; stop it (systemctl stop postgresql) so it cannot run next to the" >&2
    echo "benchmarks, or set ALLOW_LOCAL_POSTGRES=1." >&2
    exit 3
fi
GOVERNOR="$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unknown)"
if [ "$GOVERNOR" != "performance" ]; then
    echo "WARNING: the CPU governor is '$GOVERNOR', not 'performance' (see the TOSEM setup-server.sh)" >&2
fi

# ---------------------------------------------------------------- the tests of every block
# One line per test: <TestClassName> [-D options]. Options with spaces are not supported.
block_tests() {
    case "$1" in
        smoke)
            echo "SanityBenchmarkTest -Djmh.forks=1 -Djmh.warmup=1 -Djmh.iters=1 -Djmh.secs=1"
            echo "TimeRecencyExperimentTest -Drs.reps=1 -Drs.recency.rates=100"
            echo "DropwizardAlphaExperimentTest -Drs.reps=1 -Drs.alpha.claimReps=20 -Drs.alpha.tradeoffReps=4 -Drs.alpha.windowSeconds=300"
            echo "ConcurrentRecordingAccuracyExperimentTest -Drs.reps=1 -Drs.live.seconds=3 -Drs.live.stallProbabilities=0.002,0.00001"
            echo "QuantileAccuracyExperimentTest -Drs.reps=2 -Drs.maxStream=10000"
            echo "SamplesForTargetErrorExperimentTest -Drs.reps=3 -Drs.stream=20000"
            echo "ForwardDecayAlphaSensitivityExperimentTest -Drs.reps=2 -Drs.stream=20000"
            echo "VirtualThreadUpdateExperimentTest -Drs.reps=1 -Drs.vt.millis=1000"
            echo "VirtualThreadRecorderBenchmarkTest -Djmh.forks=1 -Djmh.warmup=1 -Djmh.iters=4 -Djmh.secs=3 -Drs.vt.scrapeMillis=2000 -Djmh.param.recorder=none,Dropwizard-ExpDecay,Dropwizard-SlidingWindow,Micrometer-Summary,Prometheus-CKMS,OpenTelemetry-Exponential -Djmh.param.clients=200,10000 -Djmh.param.ioMicros=1000"
            echo "UpdateThroughputBenchmarkTest -Djmh.forks=1 -Djmh.warmup=1 -Djmh.iters=1 -Djmh.secs=1 -Djmh.param.k=1028 -Djmh.param.strategy=$OTEL"
            ;;
        vt-jmh)
            echo "VirtualThreadRecorderBenchmarkTest $VT_BUDGET"
            ;;
        vt-jmh-long)
            echo "VirtualThreadRecorderBenchmarkTest $VT_LONG_BUDGET"
            ;;
        vt-update)
            echo "VirtualThreadUpdateExperimentTest -Drs.reps=5"
            ;;
        vt-live)
            echo "ConcurrentRecordingAccuracyExperimentTest -Drs.reps=5 -Drs.live.seconds=30"
            ;;
        accuracy)
            echo "QuantileAccuracyExperimentTest"
            echo "SamplesForTargetErrorExperimentTest"
            echo "ForwardDecayAlphaSensitivityExperimentTest"
            echo "AlphaConvergenceExperimentTest"
            echo "RecencyExperimentTest"
            echo "TimeRecencyExperimentTest"
            echo "DropwizardAlphaExperimentTest"
            ;;
        part1-jmh)
            echo "UpdateThroughputBenchmarkTest $PART1_BUDGET -Djmh.profGc=true"
            echo "ConcurrentUpdateBenchmarkTest $PART1_BUDGET"
            echo "BatchedInsertionBenchmarkTest $PART1_BUDGET"
            echo "QuantileQueryBenchmarkTest $PART1_BUDGET"
            echo "ConcurrentQueryBenchmarkTest $PART1_BUDGET"
            ;;
        starvation)
            echo "StarvationDriverValidationExperimentTest"
            echo "VirtualThreadPoolExperimentTest"
            echo "ReservoirOverheadExperimentTest"
            echo "AdmissionControlExperimentTest"
            ;;
        starvation-v1)
            echo "VirtualThreadPoolExperimentTest"
            ;;
        accuracy-e6)
            echo "QuantileAccuracyExperimentTest"
            ;;
        starvation-smoke)
            echo "VirtualThreadPoolExperimentTest $STARVATION_SMOKE"
            echo "ReservoirOverheadExperimentTest $STARVATION_SMOKE"
            echo "AdmissionControlExperimentTest $STARVATION_SMOKE"
            ;;
        tail-replay)
            echo "AcquisitionTailAccuracyExperimentTest"
            ;;
        tail-replay-smoke)
            echo "AcquisitionTailAccuracyExperimentTest -Drs.tail.replays=3"
            ;;
        otel-jmh)
            echo "VirtualThreadRecorderBenchmarkTest $VT_BUDGET -Djmh.param.recorder=$OTEL_V6"
            echo "UpdateThroughputBenchmarkTest $VT_BUDGET -Djmh.profGc=true -Djmh.param.k=1028 -Djmh.param.strategy=$OTEL_COST"
            echo "ConcurrentUpdateBenchmarkTest $VT_BUDGET -Djmh.param.strategy=$OTEL_COST"
            echo "QuantileQueryBenchmarkTest $VT_BUDGET -Djmh.param.strategy=$OTEL_COST"
            echo "ConcurrentQueryBenchmarkTest $VT_BUDGET -Djmh.param.strategy=$OTEL_CONCURRENT"
            ;;
        otel-accuracy)
            echo "QuantileAccuracyExperimentTest"
            echo "SamplesForTargetErrorExperimentTest"
            echo "RecencyExperimentTest"
            echo "TimeRecencyExperimentTest"
            ;;
        *)
            return 1
            ;;
    esac
}

for block in "$@"; do
    if ! block_tests "$block" > /dev/null; then
        echo "Unknown block: $block" >&2
        exit 2
    fi
done

# Block-specific settings: the database of the starvation block and the traces of the replay. The password is
# read from JDBC_PASSWORD by the harness, so it never appears on a command line or in the per-process log.
block_options() {
    case "$1" in
        starvation|starvation-smoke|starvation-v1)
            echo "-Dusl.db.url=jdbc:postgresql://$DB_HOST/uslpool"
            ;;
        tail-replay|tail-replay-smoke)
            echo "-Drs.acquisitionTraces=$TRACES_DIR"
            ;;
    esac
}

FAILED=0
for block in "$@"; do
    case "$block" in
        starvation|starvation-smoke|starvation-v1)
            : "${DB_HOST:?Set DB_HOST to the database server IP for the starvation block}"
            : "${JDBC_PASSWORD:?Set JDBC_PASSWORD to the postgres password of the database server}"
            export JDBC_PASSWORD
            ;;
        tail-replay|tail-replay-smoke)
            # The traces of the newest block of the matching kind on this host (results/starvation-<host>-<time> for
            # tail-replay, results/starvation-smoke-<host>-<time> for its smoke), so neither reads the other one.
            source_block="starvation"
            [ "$block" = tail-replay-smoke ] && source_block="starvation-smoke"
            block_traces="${TRACES:-$(ls -td results/"$source_block-$HOST"-*/metrics/VirtualThreads/traces 2>/dev/null | head -1)}"
            : "${block_traces:?Set TRACES to the metrics/VirtualThreads/traces folder of a starvation block}"
            TRACES_DIR="$(cd "$block_traces" && pwd)"
            ;;
    esac

    DIR="results/$block-$HOST-$(date -u +%Y%m%dT%H%M%SZ)"
    mkdir -p "$DIR/metrics"
    DIR="$(cd "$DIR" && pwd)"
    echo "=== block $block on $HOST -> $DIR"
    bash "$BUNDLE/server-record.sh" > "$DIR/server-record-start.txt" 2>&1
    (cd "$BUNDLE" && sha256sum ./*.jar lib/*.jar) > "$DIR/bundle.sha256"
    echo "test,start_utc,end_utc,exit_code" > "$DIR/status.csv"

    if [ "$block" = starvation ] || [ "$block" = starvation-smoke ] || [ "$block" = starvation-v1 ]; then
        if [ -n "${TELEMETRY_AGENT_URL:-}" ] && ! curl -fsS --max-time 5 "${TELEMETRY_AGENT_URL%/}/now" > /dev/null; then
            echo "WARNING: the telemetry agent at $TELEMETRY_AGENT_URL does not answer (start-agent.sh on the database" \
                 "host); the block runs without the database host's telemetry" | tee -a "$DIR/console.log"
            unset TELEMETRY_AGENT_URL
        fi
    else
        unset TELEMETRY_AGENT_URL
    fi
    bash "$BUNDLE/telemetry-log.sh" "$DIR/telemetry.csv" 10 > "$DIR/telemetry.log" 2>&1 &
    TELEMETRY_PID=$!

    PIDSTAT_PID=""
    if command -v pidstat > /dev/null; then
        pidstat -u -h -l 10 > "$DIR/pidstat.log" 2>&1 &
        PIDSTAT_PID=$!
    else
        echo "WARNING: pidstat not found (install sysstat); no per-process log" | tee -a "$DIR/console.log"
    fi

    while read -r test options; do
        [ -n "$test" ] || continue
        start="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "--- $test $options ($start)" | tee -a "$DIR/console.log"
        # shellcheck disable=SC2086
        (cd "$DIR" && bash "$BUNDLE/run.sh" "$test" $(block_options "$block") $options < /dev/null) 2>&1             | tee -a "$DIR/console.log"
        code=${PIPESTATUS[0]}
        echo "$test,$start,$(date -u +%Y-%m-%dT%H:%M:%SZ),$code" >> "$DIR/status.csv"
        if [ "$code" != "0" ]; then
            FAILED=1
            echo "!!! $test ended with exit code $code" | tee -a "$DIR/console.log"
        fi
    done < <(block_tests "$block")

    [ -n "$PIDSTAT_PID" ] && kill "$PIDSTAT_PID" 2>/dev/null
    kill "$TELEMETRY_PID" 2>/dev/null
    bash "$BUNDLE/server-record.sh" > "$DIR/server-record-end.txt" 2>&1
    echo "=== block $block done: $(grep -c ',0$' "$DIR/status.csv") of $(($(wc -l < "$DIR/status.csv") - 1)) tests passed"
done
exit $FAILED
