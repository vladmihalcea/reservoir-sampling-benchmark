#!/usr/bin/env bash
# Starts run-campaign.sh as the transient system service rs-campaign, outside the ssh session, so a disconnect does
# not stop it and no login shell runs next to the forks. Run as root on the host that runs the benchmarks, with the
# postgres password of the database host on the first line of stdin (an empty line when no block needs the
# database). The password goes to a root-only environment file, never on a command line; the output goes to
# /root/rs-campaign-<name>.log, and the results to /root/results.
#
#   bash start-campaign.sh <name> "<blocks>" [<database host IP>] < password-line
#
# Follow it with systemctl status rs-campaign and the log; stop it with systemctl stop rs-campaign (the current
# test is killed, the finished results folders stay).
set -euo pipefail
BUNDLE="$(cd "$(dirname "$0")" && pwd)"
name="${1:?Give a name for the log}"
blocks="${2:?Give the blocks, e.g. \"smoke vt-jmh\"}"
db_host="${3:-}"
read -r password || true
umask 077
env_file=/root/.rs-campaign.env
{
    echo "JDBC_PASSWORD=$password"
    echo "HOME=/root"
    if [ -n "$db_host" ]; then
        echo "DB_HOST=$db_host"
        echo "TELEMETRY_AGENT_URL=http://$db_host:9500"
    fi
} > "$env_file"
log="/root/rs-campaign-$name.log"
# shellcheck disable=SC2086
systemd-run --unit=rs-campaign --collect \
    --property=EnvironmentFile="$env_file" --property=WorkingDirectory=/root \
    --property=StandardOutput=append:"$log" --property=StandardError=append:"$log" \
    bash "$BUNDLE/run-campaign.sh" $blocks && echo "Started the rs-campaign service ($blocks), log $log"
