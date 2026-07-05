@echo off
REM Run the deterministic accuracy experiments (E6-E8 + the k-sweep and alpha-convergence headliners) and
REM the memory footprint report (E4-retained). Produces CSVs under metrics/QuantileAccuracy,
REM metrics/SamplesForAccuracy, metrics/AlphaSensitivity, metrics/AlphaConvergence, metrics/Recency and
REM metrics/MemoryFootprint. Pass -Drs.reps=N (and -Drs.maxStream=N) to shrink for a quick pass.

pushd core
call mvn -P benchmark-tests clean process-test-resources

call mvn -P benchmark-tests -Dit.test=QuantileAccuracyExperimentTest integration-test %* &if not errorlevel 1 ^
call mvn -P benchmark-tests -Dit.test=SamplesForTargetErrorExperimentTest integration-test %* &if not errorlevel 1 ^
call mvn -P benchmark-tests -Dit.test=ForwardDecayAlphaSensitivityExperimentTest integration-test %* &if not errorlevel 1 ^
call mvn -P benchmark-tests -Dit.test=AlphaConvergenceExperimentTest integration-test %* &if not errorlevel 1 ^
call mvn -P benchmark-tests -Dit.test=RecencyExperimentTest integration-test %* &if not errorlevel 1 ^
call mvn -P benchmark-tests -Dit.test=MemoryFootprintReportTest integration-test %*
popd
