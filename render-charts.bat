@echo off
REM Build the JMH-derived figure CSVs from metrics\jmh-results, then render every gnuplot script under
REM metrics\ to SVG + PDF (requires gnuplot on PATH).

setlocal enabledelayedexpansion

REM 1) Reshape JMH long-format results into the chart-ready wide CSVs (no-op if benchmarks not yet run).
if exist "core\target\classes" (
    echo Building figure CSVs from metrics\jmh-results ...
    java -cp core\target\classes com.vladmihalcea.phd.reservoir.util.FiguresFromJmh
) else (
    echo Skipping figure-CSV build: core\target\classes not found ^(run a Maven build first^).
)

REM 2) Render every .gp beside its CSV.
for /r metrics %%f in (*.gp) do (
    echo Rendering %%f
    pushd "%%~dpf"
    gnuplot "%%~nxf"
    popd
)
endlocal
