@echo off
REM Run all JMH performance benchmark families (E1-E3, E5). Produces JSON + CSV under metrics/jmh-results.
REM E1 runs with the GC profiler (-Djmh.profGc=true) so gc.alloc.rate.norm (bytes/update) is captured
REM for the allocation-rate half of the E4 memory figure.

pushd core
call mvn -P benchmark-tests clean process-test-resources

call mvn -P benchmark-tests -Dit.test=UpdateThroughputBenchmarkTest -Djmh.profGc=true integration-test &if not errorlevel 1 ^
call mvn -P benchmark-tests -Dit.test=ConcurrentUpdateBenchmarkTest integration-test &if not errorlevel 1 ^
call mvn -P benchmark-tests -Dit.test=BatchedInsertionBenchmarkTest integration-test &if not errorlevel 1 ^
call mvn -P benchmark-tests -Dit.test=QuantileQueryBenchmarkTest integration-test &if not errorlevel 1 ^
call mvn -P benchmark-tests -Dit.test=ConcurrentQueryBenchmarkTest integration-test
popd
