# Experiment E5: cost of reading p99 per strategy from a warm structure (JMH AverageTime, ns/op).
# Input:  query-cost.csv  (strategy, query_ns, err)
#         produced by FiguresFromJmh from metrics/jmh-results/QuantileQueryBenchmark.csv
# Output: query-cost.svg / .pdf
#
# Sampling reservoirs pay a sort at query time; histograms pay a bucket scan; CKMS pays at record time so
# its query is cheap. Read together with the update-cost figure for total cost of ownership.

set datafile separator ","
set ylabel "Nanoseconds per query (log scale)" font ",12"
set logscale y
set grid ytics lt 0 lw 0.5 lc rgb "#cccccc"

set style data histograms
set style histogram gap 1
# The bars and the error bars are drawn as two layers rather than with "set style histogram errorbars":
# that style places the whisker roughly a bar-pitch to the left of the box it belongs to, so the caps
# float in the gaps between bars. Plotting the whiskers separately against the row index ($0) puts them
# on their own bar. Note the caps look like detached dashes because the intervals are narrow (0.6-5% of
# the value) and the axis is logarithmic, so the vertical span collapses at print size.
set bars 1.5
# Histogram style pads the x-range by a full slot on each side, leaving a blank column against the
# y-axis. Trim the left pad only: the right pad is what keeps the last rotated xtic label inside the
# canvas, so clamping that end instead clips the label.
set offsets -1.5, 0, 0, 0
set style fill solid 0.9 border -1
set boxwidth 0.7
set xtics rotate by -40 font ",11"
set ytics font ",11"
unset key

set terminal svg enhanced font "arial,11" size 1100,600
set output 'query-cost.svg'
plot 'query-cost.csv' using 2:xtic(1) lc rgb "#00838F" notitle, \
     ''               using ($0):2:3 with yerrorbars pt 0 lw 1.5 lc rgb "#000000" notitle

set terminal pdfcairo enhanced font "arial,12" size 5,2.8
set output 'query-cost.pdf'
replot

set output
