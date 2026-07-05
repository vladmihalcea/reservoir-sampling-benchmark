# Experiment E1: per-update cost per strategy, clustered by reservoir size k (JMH AverageTime, ns/op).
# Input:  update-cost.csv  (strategy, k128, k512, k1028, k4096) = ns/op
#         produced by FiguresFromJmh from metrics/jmh-results/UpdateThroughputBenchmark.csv
# Output: update-cost.svg / .pdf
#
# The per-element Efraimidis-Spirakis strategies rise with k (a random draw and heap sift per update);
# Algorithm L and the histograms stay flat, which is the core per-element cost story of the survey.

set datafile separator ","
set title "Per-update cost by strategy and reservoir size k" font ",13"
set ylabel "Nanoseconds per update (log scale)"
set logscale y
set grid ytics lt 0 lw 0.5 lc rgb "#cccccc"

set style data histograms
set style histogram clustered gap 1
set style fill solid 0.9 border -1
set boxwidth 0.9
set xtics rotate by -40 font ",8"
set key outside right top vertical

set style line 1 lc rgb "#64B5F6"
set style line 2 lc rgb "#1565C0"
set style line 3 lc rgb "#2E7D32"
set style line 4 lc rgb "#6A1B9A"

set terminal svg enhanced font "arial,11" size 1200,600
set output 'update-cost.svg'
plot for [i=2:5] 'update-cost.csv' using i:xtic(1) ls (i-1) title columnheader

set terminal pdfcairo enhanced font "arial,9" size 9,5
set output 'update-cost.pdf'
replot

set output
