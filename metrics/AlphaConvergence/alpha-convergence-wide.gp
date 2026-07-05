# Experiment E7b (C5): p99 convergence after a regime shift for a range of forward-decay alpha values.
# Input:  alpha-convergence-wide.csv  (post_shift_updates, a=0.001, a=0.005, a=0.015, a=0.05, a=0.1)
# Output: alpha-convergence-wide.svg / .pdf
#
# Larger alpha forgets the old regime faster (converges sooner) but with a noisier steady state; the
# hardcoded a=0.015 curve is highlighted so its fixed, workload-independent position is visible. x is
# offset by +1 so the "0 post-shift updates" checkpoint shows on the log axis.

set datafile separator ","
set title "p99 convergence after a regime shift, by forward-decay alpha" font ",13"
set xlabel "Observations since shift (log scale)"
set ylabel "Mean p99 relative error"
set logscale x
set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
set key outside right top vertical

set style line 1 lw 2 lc rgb "#64B5F6"
set style line 2 lw 2 lc rgb "#2E7D32"
set style line 3 lw 3 lc rgb "#C62828"
set style line 4 lw 2 lc rgb "#F9A825"
set style line 5 lw 2 lc rgb "#6A1B9A"

set terminal svg enhanced font "arial,11" size 1000,550
set output 'alpha-convergence-wide.svg'
plot for [i=2:6] 'alpha-convergence-wide.csv' using ($1+1):i with linespoints ls (i-1) title columnheader

set terminal pdfcairo enhanced font "arial,10" size 8,4.5
set output 'alpha-convergence-wide.pdf'
replot

set output
