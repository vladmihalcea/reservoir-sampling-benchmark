# Experiment E4 (retained memory): deep object-graph footprint per strategy after 1,000,000 updates,
# clustered by reservoir size k.
# Input:  MemoryFootprint-wide.csv  (strategy, k128, k512, k1028, k4096) = retained_bytes
# Output: MemoryFootprint.svg / .pdf
#
# Bounded reservoirs should grow ~linearly with k; histogram / sliding-window structures are k-independent
# (flat across the cluster) and either tiny (fixed buckets) or towering (unbounded window).

set datafile separator ","
set title "Retained memory per strategy by reservoir size k (after 1e6 updates)" font ",13"
set ylabel "Retained bytes (log scale)"
set logscale y
set format y "10^{%L}"
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
set output 'MemoryFootprint.svg'
plot for [i=2:5] 'MemoryFootprint-wide.csv' using i:xtic(1) ls (i-1) title columnheader

set terminal pdfcairo enhanced font "arial,9" size 9,5
set output 'MemoryFootprint.pdf'
replot

set output
