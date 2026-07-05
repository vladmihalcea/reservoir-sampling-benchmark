@echo off
REM Run the fast unit tests only (excludes JMH benchmarks and heavy experiments).

pushd core
mvn test %*
popd
