#!/usr/bin/env bash
# Sourced by the launchers: sets JAVA to a Java 25 runtime (JAVA_HOME, else /opt/temurin-25, else java on the PATH)
# and CORE_CP to the core class path of the bundle. Exits with code 2 when no Java 25 is found.
BUNDLE="${BUNDLE:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif [ -x /opt/temurin-25/bin/java ]; then
    JAVA=/opt/temurin-25/bin/java
else
    JAVA=java
fi
if ! "$JAVA" -version 2>&1 | head -1 | grep -q '"25'; then
    echo "Java 25 is required, found: $("$JAVA" -version 2>&1 | head -1) ($JAVA). Run setup-jdk25.sh or set JAVA_HOME." >&2
    exit 2
fi
CORE_CP="$BUNDLE/core.jar:$BUNDLE/core-tests.jar:$BUNDLE/lib/*"
