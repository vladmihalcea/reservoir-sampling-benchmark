# Experiment E8: how fast each strategy's p99 tracks a step change in the latency distribution.
# Input:  recency-wide.csv  (post_shift_updates, <one column per strategy> = mean p99 rel error)
# Output: recency.svg / .pdf
#
# x is offset by +1 so the "0 post-shift updates" checkpoint is visible on the log axis.

set datafile separator ","
set title "p99 convergence after a regime shift" font ",13"
set xlabel "Observations since shift (log scale)"
set ylabel "Mean p99 relative error"
set logscale x
set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
set key outside right top vertical

set style line 1 lw 2 lc rgb "#1565C0"
set style line 2 lw 2 lc rgb "#00838F"
set style line 3 lw 2 lc rgb "#2E7D32"
set style line 4 lw 2 lc rgb "#F9A825"
set style line 5 lw 2 lc rgb "#6A1B9A"
set style line 6 lw 2 lc rgb "#C62828"

set terminal svg enhanced font "arial,11" size 1000,550
set output 'recency.svg'
plot for [i=2:7] 'recency-wide.csv' using ($1+1):i with linespoints ls (i-1) title columnheader

set terminal pdfcairo enhanced font "arial,10" size 8,4.5
set output 'recency.pdf'
replot

set output
