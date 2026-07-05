# Experiment E6: quantile accuracy per strategy on the HTTP-latency workload.
# Input:  bars-http-latency.csv  (strategy, p50, p90, p95, p99, p999 = mean relative error)
# Output: accuracy-http-latency.svg / .pdf
#
# Re-run the accuracy experiment first: run-accuracy-experiments.bat

set datafile separator ","
set title "Quantile accuracy by strategy - HTTP latency workload" font ",13"
set ylabel "Mean relative error"
set yrange [0:*]
set grid ytics lt 0 lw 0.5 lc rgb "#cccccc"

set style data histograms
set style histogram clustered gap 1
set style fill solid 0.9 border -1
set boxwidth 0.9
set key outside right top vertical
set xtics rotate by -40 font ",8"

set terminal svg enhanced font "arial,11" size 1300,600
set output 'accuracy-http-latency.svg'
plot for [i=2:6] 'bars-http-latency.csv' using i:xtic(1) title columnheader

set terminal pdfcairo enhanced font "arial,9" size 11,5
set output 'accuracy-http-latency.pdf'
replot

set output
