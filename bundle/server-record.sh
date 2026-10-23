#!/usr/bin/env bash
# Writes the state of this server that a run depends on to stdout: hardware, kernel, CPU frequency policy and idle
# states, limits that 10,000 platform threads need, memory, the Java runtime, and what else is running. The campaign
# script stores it at the start and at the end of every block, so the two records of a block can be compared.
set -uo pipefail

section() { printf '\n## %s\n' "$1"; }

section "time"
date -u '+%Y-%m-%dT%H:%M:%SZ'
uptime

section "host"
hostname
uname -a
cat /etc/os-release 2>/dev/null | grep -E '^(PRETTY_NAME|VERSION_ID)='
cat /sys/class/dmi/id/product_name /sys/class/dmi/id/bios_version 2>/dev/null

section "cpu"
lscpu
echo "governor: $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null)"
echo "energy_performance_preference: $(cat /sys/devices/system/cpu/cpu0/cpufreq/energy_performance_preference 2>/dev/null)"
echo "intel_pstate no_turbo: $(cat /sys/devices/system/cpu/intel_pstate/no_turbo 2>/dev/null)"
for state in /sys/devices/system/cpu/cpu0/cpuidle/state*; do
    [ -d "$state" ] || continue
    echo "idle $(cat "$state/name") latency=$(cat "$state/latency")us disabled=$(cat "$state/disable")"
done
grep -h . /sys/devices/system/cpu/cpu0/thermal_throttle/*_count 2>/dev/null | paste -sd' ' | sed 's/^/thermal throttle counts (core package): /'

section "memory and limits"
free -m
echo "kernel.threads-max: $(cat /proc/sys/kernel/threads-max)"
echo "kernel.pid_max: $(cat /proc/sys/kernel/pid_max)"
echo "vm.max_map_count: $(cat /proc/sys/vm/max_map_count)"
echo "transparent_hugepage: $(cat /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null)"
ulimit -a

section "java"
if [ -n "${JAVA_HOME:-}" ]; then "$JAVA_HOME/bin/java" -version 2>&1; fi
[ -x /opt/temurin-25/bin/java ] && /opt/temurin-25/bin/java -version 2>&1

section "services"
for unit in postgresql rs-agent rs-campaign unattended-upgrades thermald snapd; do
    echo "$unit: $(systemctl is-active "$unit" 2>/dev/null)"
done

section "network"
ip -brief address 2>/dev/null

section "busiest processes"
ps -eo pid,user,pcpu,pmem,etime,args --sort=-pcpu | head -15
