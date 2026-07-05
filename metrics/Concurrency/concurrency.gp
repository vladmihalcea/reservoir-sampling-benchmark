# Experiment E2: aggregate update throughput vs. thread count, one line per strategy (JMH Throughput).
# Input:  concurrency.csv  (threads, <one column per strategy> = ops/us)
#         produced by FiguresFromJmh from metrics/jmh-results/ConcurrentUpdateBenchmark-t*.csv
# Output: concurrency.svg / .pdf
#
# The lock-guarded single-writer reservoirs flatten or degrade as threads rise (lock contention); the
# internally lock-free / synchronised framework metrics should scale better — the survey's contention
# contrast, which an earlier uniform-lock version of the benchmark hid.

set datafile separator ","
set title "Update throughput under contention (1-16 threads)" font ",13"
set xlabel "Threads (log scale)"
set ylabel "Throughput (ops/us)"
set logscale x 2
set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
set key outside right top vertical font ",8"

set style line 1 lw 2 lc rgb "#1565C0"
set style line 2 lw 2 lc rgb "#00838F"
set style line 3 lw 2 lc rgb "#2E7D32"
set style line 4 lw 2 lc rgb "#F9A825"
set style line 5 lw 2 lc rgb "#6A1B9A"
set style line 6 lw 2 lc rgb "#C62828"
set style line 7 lw 2 lc rgb "#E57373"
set style line 8 lw 2 lc rgb "#81C784"
set style line 9 lw 2 lc rgb "#64B5F6"
set style line 10 lw 2 lc rgb "#F06292"

set terminal svg enhanced font "arial,11" size 1000,550
set output 'concurrency.svg'
plot for [i=2:11] 'concurrency.csv' using 1:i with linespoints ls (i-1) title columnheader

set terminal pdfcairo enhanced font "arial,10" size 8,4.5
set output 'concurrency.pdf'
replot

set output
