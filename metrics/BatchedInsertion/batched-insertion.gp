# Experiment E3: insertion time for 1e6 values, batched WRS-B against the per-element baselines
# (JMH AverageTime).
# Input:  batched-insertion.csv  (batchSize, <one column per benchmark method> = us/op, as emitted by JMH;
#         divided by 1000 below so the plotted axis is in ms, matching the paper's text and caption)
#         produced by FiguresFromJmh from metrics/jmh-results/BatchedInsertionBenchmark.csv
# Output: batched-insertion.svg / .pdf
#
# Deliberately a clustered bar chart, not a line chart. Batch size is a parameter of WrsBBatched ONLY;
# the three per-element strategies never read it, so plotting them as lines across the batch-size axis
# would imply a dependence that does not exist. They are therefore drawn once, in the batch=1 cluster,
# where every strategy is per-element and the comparison is like-for-like: WRS-B at batch 1 degenerates
# to A-ExpJ (the same exponential-jump acceptance rule, unbatched) and the two bars coincide. The
# remaining clusters carry WRS-B alone, showing what batching buys against those fixed references.
# The 1/0 guard below yields an undefined value, which gnuplot skips, leaving those slots empty.

set datafile separator ","
set xlabel "Batch size" font ",12"
set ylabel "Total insertion time, ms (log scale)" font ",12"
set logscale y
set yrange [1:120]
set grid ytics lt 0 lw 0.5 lc rgb "#cccccc"

set style data histograms
set style histogram clustered gap 1
set style fill solid 0.9 border -1
set boxwidth 0.9
set xtics font ",11" offset 2.2,0
set ytics font ",11"
set bmargin at screen 0.26
set key at screen 0.5, 0.055 center horizontal maxrows 2 font ",11" samplen 1.5 spacing 1.1

set style line 1 lc rgb "#1565C0"
set style line 2 lc rgb "#C62828"
set style line 3 lc rgb "#F9A825"
set style line 4 lc rgb "#2E7D32"

set terminal svg enhanced font "arial,11" size 1000,550
set output 'batched-insertion.svg'
# Series are ordered alphabetically, which gnuplot uses for both the cluster slots and the key. Titles are
# set explicitly rather than read from columnheader: the CSV carries JMH method names, whereas the paper
# and Figs. 1, 2 and 4 name these strategies Dropwizard-ExpDecay, ES-A-ExpJ, ES-A-Res and WRS-B.
# "(per-element)" marks the three that never batch; WRS-B carries no such tag because at batch 1 it is
# unbatched as well, which is precisely what the leftmost cluster shows.
plot 'batched-insertion.csv' every ::1 using ($1==1 ? $5/1000.0 : 1/0):xtic(1) ls 4 title "Dropwizard-ExpDecay (per-element)", \
     ''                      every ::1 using ($1==1 ? $3/1000.0 : 1/0) ls 2 title "ES-A-ExpJ (per-element)", \
     ''                      every ::1 using ($1==1 ? $4/1000.0 : 1/0) ls 3 title "ES-A-Res (per-element)", \
     ''                      every ::1 using ($2/1000.0) ls 1 title "WRS-B"

set terminal pdfcairo enhanced font "arial,12" size 5,2.9
set output 'batched-insertion.pdf'
replot

set output
