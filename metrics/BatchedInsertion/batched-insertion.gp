# Experiment E3: bulk-load time for 1e6 values vs. batch size, per insertion method (JMH AverageTime, ms).
# Input:  batched-insertion.csv  (batchSize, <one column per benchmark method> = ms/op)
#         produced by FiguresFromJmh from metrics/jmh-results/BatchedInsertionBenchmark.csv
# Output: batched-insertion.svg / .pdf
#
# This is the C4 "order-of-magnitude speedup" figure: the per-element Efraimidis-Spirakis and Dropwizard
# ExpDecay lines are flat and high; batched WRS-B drops as the batch grows, widening the gap.

set datafile separator ","
set title "Bulk insertion time (1e6 values) vs. batch size" font ",13"
set xlabel "Batch size (log scale)"
set ylabel "Total insertion time, ms (log scale)"
set logscale x 10
set logscale y
set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
set key outside right top vertical

set style line 1 lw 2 pt 7 lc rgb "#1565C0"
set style line 2 lw 2 pt 5 lc rgb "#C62828"
set style line 3 lw 2 pt 9 lc rgb "#F9A825"
set style line 4 lw 2 pt 11 lc rgb "#2E7D32"

set terminal svg enhanced font "arial,11" size 1000,550
set output 'batched-insertion.svg'
plot for [i=2:5] 'batched-insertion.csv' using 1:i with linespoints ls (i-1) title columnheader

set terminal pdfcairo enhanced font "arial,10" size 8,4.5
set output 'batched-insertion.pdf'
replot

set output
