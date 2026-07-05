# Experiment E5: cost of reading p99 per strategy from a warm structure (JMH AverageTime, ns/op).
# Input:  query-cost.csv  (strategy, query_ns, err)
#         produced by FiguresFromJmh from metrics/jmh-results/QuantileQueryBenchmark.csv
# Output: query-cost.svg / .pdf
#
# Sampling reservoirs pay a sort at query time; histograms pay a bucket scan; CKMS pays at record time so
# its query is cheap. Read together with the update-cost figure for total cost of ownership.

set datafile separator ","
set title "Query cost: reading p99 per strategy" font ",13"
set ylabel "Nanoseconds per query (log scale)"
set logscale y
set grid ytics lt 0 lw 0.5 lc rgb "#cccccc"

set style data histograms
set style histogram errorbars gap 1 lw 1.5
set style fill solid 0.9 border -1
set boxwidth 0.7
set xtics rotate by -40 font ",8"
unset key

set terminal svg enhanced font "arial,11" size 1100,600
set output 'query-cost.svg'
plot 'query-cost.csv' using 2:3:xtic(1) lc rgb "#00838F" notitle

set terminal pdfcairo enhanced font "arial,9" size 9,5
set output 'query-cost.pdf'
replot

set output
