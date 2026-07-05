# Experiment E6b (C2): p99 relative error vs. reservoir size k on the Pareto (heavy-tail) workload,
# one line per k-tunable strategy.
# Input:  error-wide-pareto.csv  (k, <one column per strategy> = mean p99 rel error)
# Output: error-wide-pareto.svg / .pdf
#
# The x-position where each curve crosses the target error line is that strategy's "samples for accuracy";
# strategies that need a much larger k to cross pay that as memory and per-update cost.

set datafile separator ","
set title "p99 error vs. reservoir size k (Pareto tail)" font ",13"
set xlabel "Reservoir size k (log scale)"
set ylabel "Mean p99 relative error"
set logscale x 2
set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
set key outside right top vertical font ",8"

set arrow from graph 0, first 0.05 to graph 1, first 0.05 nohead dt 2 lc rgb "#888888"
set label "target 0.05" at graph 0.02, first 0.055 font ",8" tc rgb "#888888"

set style line 1 lw 2 lc rgb "#1565C0"
set style line 2 lw 2 lc rgb "#00838F"
set style line 3 lw 2 lc rgb "#2E7D32"
set style line 4 lw 2 lc rgb "#F9A825"
set style line 5 lw 2 lc rgb "#6A1B9A"
set style line 6 lw 2 lc rgb "#C62828"
set style line 7 lw 2 lc rgb "#E57373"
set style line 8 lw 2 lc rgb "#81C784"

set terminal svg enhanced font "arial,11" size 1000,550
set output 'error-wide-pareto.svg'
plot for [i=2:9] 'error-wide-pareto.csv' using 1:i with linespoints ls (i-1) title columnheader

set terminal pdfcairo enhanced font "arial,10" size 8,4.5
set output 'error-wide-pareto.pdf'
replot

set output
