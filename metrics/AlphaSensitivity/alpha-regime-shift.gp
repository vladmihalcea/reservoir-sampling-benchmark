# Experiment E7: sensitivity of forward-decay tail-quantile error to alpha (regime-shift workload).
# Input:  alpha-wide-regime-shift.csv  (alpha, p95, p99, p999 = mean relative error vs recent truth)
# Output: alpha-regime-shift.svg / .pdf
#
# The vertical marker at alpha = 0.015 is Dropwizard's hardcoded value; the point of the figure is to
# show whether it sits at the error minimum for this workload.

set datafile separator ","
set title "Forward-decay tail error vs alpha - regime-shift workload" font ",13"
set xlabel "Decay parameter {/Symbol a} (log scale)"
set ylabel "Mean relative error vs recent truth"
set logscale x
set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
set key top left

set arrow from 0.015, graph 0 to 0.015, graph 1 nohead lc rgb "#C62828" dt 2
set label "Dropwizard 0.015" at 0.015, graph 0.95 right rotate by 90 tc rgb "#C62828" font ",8"

set terminal svg enhanced font "arial,11" size 900,550
set output 'alpha-regime-shift.svg'
plot 'alpha-wide-regime-shift.csv' using 1:2 with linespoints lw 2 lc rgb "#1565C0" title "p95", \
     '' using 1:3 with linespoints lw 2 lc rgb "#2E7D32" title "p99", \
     '' using 1:4 with linespoints lw 2 lc rgb "#C62828" title "p999"

set terminal pdfcairo enhanced font "arial,10" size 7.5,4.5
set output 'alpha-regime-shift.pdf'
replot

set output
