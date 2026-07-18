# Experiment E1: per-update cost per strategy, clustered by reservoir size k (JMH AverageTime, ns/op).
# Input:  update-cost.csv  (strategy, k128, k512, k1028, k4096) = ns/op
#         produced by FiguresFromJmh from metrics/jmh-results/UpdateThroughputBenchmark.csv
# Output: update-cost.svg / .pdf
#
# Cost is essentially flat in k for every strategy: across the 32x sweep from k=128 to k=4096 no strategy
# moves by more than ~13% (ES-A-Res 16.5->17.3, Dropwizard-Uniform 8.9->10.1). The survey's point is the
# ~90x spread *between* strategies at any fixed k (Vitter-L-skip 1.6 ns vs Micrometer 142 ns), not growth
# in k, so the clusters are read across the x-axis rather than within one.

set datafile separator ","
set ylabel "Nanoseconds per update (log scale)" font ",12"
set logscale y
set grid ytics lt 0 lw 0.5 lc rgb "#cccccc"

set style data histograms
set style histogram clustered gap 1
set style fill solid 0.9 border -1
set boxwidth 0.9
set xtics rotate by -40 font ",11"
set ytics font ",11"
set key outside right top vertical font ",11" samplen 2 spacing 1.2

set style line 1 lc rgb "#64B5F6"
set style line 2 lc rgb "#1565C0"
set style line 3 lc rgb "#2E7D32"
set style line 4 lc rgb "#6A1B9A"

set terminal svg enhanced font "arial,11" size 1200,600
set output 'update-cost.svg'
plot for [i=2:5] 'update-cost.csv' using i:xtic(1) ls (i-1) title columnheader

set terminal pdfcairo enhanced font "arial,12" size 5,2.8
set output 'update-cost.pdf'
replot

set output
