#!/usr/bin/env bash
# Runs one benchmark or experiment test class of the bundle in the current directory, which receives its outputs
# under ./metrics (created if missing). Needs only Java 25: JAVA_HOME, else /opt/temurin-25, else java on the PATH.
#
#   bash run.sh <TestClassName> [-Dname=value ...]
#
# Examples:
#   bash run.sh TimeRecencyExperimentTest -Drs.reps=2 -Drs.recency.rates=100
#   bash run.sh VirtualThreadRecorderBenchmarkTest -Djmh.param.clients=200 -Djmh.forks=1
#   bash run.sh VirtualThreadPoolExperimentTest -Dusl.db.url=jdbc:postgresql://<db-ip>/uslpool -Dusl.db.password=...
#
# JVM_OPTS adds options to the launcher JVM (default: an 8 GB pre-touched heap); JMH forks inherit them, and the
# benchmarks append their own (see VirtualThreadRecorderBenchmarkTest). The exit code is the launcher's: 0 when the
# test passed, 1 when it failed or was skipped by an assumption, 2 when no class matches.
set -euo pipefail

if [ $# -lt 1 ]; then
    sed -n '2,15p' "$0"
    exit 2
fi
BUNDLE="$(cd "$(dirname "$0")" && pwd)"
TEST="$1"
shift

if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif [ -x /opt/temurin-25/bin/java ]; then
    JAVA=/opt/temurin-25/bin/java
else
    JAVA=java
fi
if ! "$JAVA" -version 2>&1 | head -1 | grep -q '"25'; then
    echo "Java 25 is required, found: $("$JAVA" -version 2>&1 | head -1) ($JAVA). Run setup-jdk25.sh or set JAVA_HOME." >&2
    exit 2
fi

# The starvation tests need their own JMH benchmark list (META-INF/BenchmarkList), so their jar comes first; JMH
# reads only the first list on the class path. Everything else runs on the core class path alone.
case "$TEST" in
    StarvationDriverTest|StarvationDriverValidationExperimentTest|VirtualThreadPoolExperimentTest|\
    ReservoirOverheadExperimentTest|AdmissionControlExperimentTest)
        CP="$BUNDLE/starvation-tests.jar:$BUNDLE/starvation.jar:$BUNDLE/core.jar:$BUNDLE/core-tests.jar:$BUNDLE/lib/*"
        ;;
    *)
        CP="$BUNDLE/core.jar:$BUNDLE/core-tests.jar:$BUNDLE/lib/*"
        ;;
esac

mkdir -p metrics
# shellcheck disable=SC2086
exec "$JAVA" ${JVM_OPTS:--Xms8g -Xmx8g -XX:+AlwaysPreTouch} "$@" -cp "$CP" \
    com.vladmihalcea.phd.reservoir.bundle.BundleMain "$TEST"
