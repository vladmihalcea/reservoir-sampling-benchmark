#!/usr/bin/env bash
# Starts the telemetry agent (telemetry-agent.sh) as the transient system service rs-agent, outside the ssh session,
# with its output in /root/rs-agent-<name>.log. Run as root on the database host before the starvation block, and
# stop it afterwards with systemctl stop rs-agent.
#
#   bash start-agent.sh <name>
set -euo pipefail
BUNDLE="$(cd "$(dirname "$0")" && pwd)"
log="/root/rs-agent-${1:?Give a name for the log, e.g. starvation}.log"
systemd-run --unit=rs-agent --collect \
    --property=WorkingDirectory=/root --setenv=HOME=/root \
    --property=StandardOutput=append:"$log" --property=StandardError=append:"$log" \
    bash "$BUNDLE/telemetry-agent.sh" && echo "Started the rs-agent service, log $log"
