#!/usr/bin/env bash
# Serves the CPU telemetry of this host (the database host of the starvation block) on port 9500, the agent of the
# TOSEM harness: GET /now returns the agent clock and GET /summary?from=<time> the metrics since then, which the
# TelemetryProfiler (JMH forks) and telemetry-log.sh (every block) of the application host read as db.* metrics.
# Needs root (or read access to /dev/cpu/*/msr and the RAPL zones) for the clock and the power; the firewall of
# the TOSEM setup-server.sh already admits port 9500 from the peer. Start it with start-agent.sh.
#
#   bash telemetry-agent.sh [port]
set -euo pipefail
BUNDLE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=java-env.sh
source "$BUNDLE/java-env.sh"
exec nice -n 19 "$JAVA" -Xms64m -Xmx128m -XX:+UseSerialGC -cp "$CORE_CP" \
    com.vladmihalcea.phd.reservoir.common.telemetry.TelemetryAgent "${1:-9500}"
