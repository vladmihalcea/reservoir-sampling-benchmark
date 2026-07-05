@echo off
REM Run a single JMH benchmark family through the failsafe plugin.
REM Usage: run-benchmark.bat Sanity^|UpdateThroughput^|ConcurrentUpdate^|BatchedInsertion^|QuantileQuery

if "%1" == "" goto usage

pushd core
set type=%1
call mvn -P benchmark-tests clean process-test-resources
mvn -P benchmark-tests -Dit.test=%type%BenchmarkTest integration-test
popd
goto:eof

:usage
echo Usage: %0 Sanity^|UpdateThroughput^|ConcurrentUpdate^|BatchedInsertion^|QuantileQuery
