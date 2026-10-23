#!/usr/bin/env bash
# Installs the Temurin 25 JDK of the ICASC runs (25.0.3+9 by default, TEMURIN_VERSION to change it) into
# /opt/temurin-25, after checking the SHA-256 that Adoptium publishes next to the tarball. Run as root, once per
# server and outside any measurement window. It does not change the default java of the system, so the Temurin 21
# of the TOSEM bundles stays as it is; run.sh finds /opt/temurin-25 on its own. Running it again is harmless.
set -euo pipefail

VERSION="${TEMURIN_VERSION:-25.0.3+9}"
TARGET=/opt/temurin-25
TAG="jdk-${VERSION/+/%2B}"
FILE="OpenJDK25U-jdk_x64_linux_hotspot_${VERSION/+/_}.tar.gz"
URL="https://github.com/adoptium/temurin25-binaries/releases/download/$TAG/$FILE"

if [ -x "$TARGET/bin/java" ] && "$TARGET/bin/java" -version 2>&1 | grep -q "\"${VERSION%%+*}\""; then
    echo "Temurin $VERSION is already installed in $TARGET"
    "$TARGET/bin/java" -version
    exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
echo "Downloading $URL"
curl -fsSL -o "$WORK/$FILE" "$URL"
curl -fsSL -o "$WORK/$FILE.sha256.txt" "$URL.sha256.txt"
EXPECTED="$(awk '{print $1}' "$WORK/$FILE.sha256.txt")"
ACTUAL="$(sha256sum "$WORK/$FILE" | awk '{print $1}')"
if [ "$EXPECTED" != "$ACTUAL" ]; then
    echo "SHA-256 mismatch: expected $EXPECTED, got $ACTUAL" >&2
    exit 1
fi
echo "SHA-256 verified: $ACTUAL"

rm -rf "$TARGET.new"
mkdir -p "$TARGET.new"
tar -xzf "$WORK/$FILE" -C "$TARGET.new" --strip-components=1
rm -rf "$TARGET"
mv "$TARGET.new" "$TARGET"
"$TARGET/bin/java" -version
