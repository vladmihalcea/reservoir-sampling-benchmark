#!/usr/bin/env bash
# Writes the CPU telemetry of this host (clock from APERF/MPERF, utilization, performance limit, temperature,
# thermal throttling, package power and its limit) every 10 s to a CSV, and that of the database host as well when
# TELEMETRY_AGENT_URL names its telemetry agent. The campaign script runs it next to pidstat for every
# block, at normal priority: at nice 19 it starved for minutes next to 10,000 runnable benchmark threads, and one
# sample per second costs nothing measurable. The JMH blocks also report the same metrics per iteration through the
# TelemetryProfiler.
#
#   bash telemetry-log.sh <output.csv> [interval seconds]
set -euo pipefail
BUNDLE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=java-env.sh
source "$BUNDLE/java-env.sh"
exec "$JAVA" -Xms64m -Xmx128m -XX:+UseSerialGC -cp "$CORE_CP" \
    com.vladmihalcea.phd.reservoir.common.telemetry.TelemetryLog "$1" "${2:-10}" "${TELEMETRY_AGENT_URL:-}"
