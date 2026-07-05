@echo off
REM Build the project and run the fast unit tests (strategy correctness, VM swap, WRS-B equivalence).

mvn clean install
goto:eof
