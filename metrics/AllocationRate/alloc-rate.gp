# Experiment E4 (allocation half): bytes allocated per update at k=1028 (JMH gc.alloc.rate.norm).
# Input:  alloc-rate.csv  (strategy, alloc_bytes_per_op)
#         produced by FiguresFromJmh from metrics/jmh-results/UpdateThroughputBenchmark.csv (run with
#         -Djmh.profGc=true, which run-performance-benchmarks.bat sets for E1).
# Output: alloc-rate.svg / .pdf
#
# HdrHistogram and the fixed-bucket histograms should sit near zero (no per-update allocation); the
# forward-decay / weighted reservoirs churn boxed sample objects each admission.

set datafile separator ","
set title "Allocation per update by strategy (k=1028)" font ",13"
set ylabel "Bytes allocated per update"
set grid ytics lt 0 lw 0.5 lc rgb "#cccccc"

set style data histograms
set style fill solid 0.9 border -1
set boxwidth 0.7
set xtics rotate by -40 font ",8"
unset key

set terminal svg enhanced font "arial,11" size 1100,600
set output 'alloc-rate.svg'
plot 'alloc-rate.csv' using 2:xtic(1) lc rgb "#C62828" notitle

set terminal pdfcairo enhanced font "arial,9" size 9,5
set output 'alloc-rate.pdf'
replot

set output
