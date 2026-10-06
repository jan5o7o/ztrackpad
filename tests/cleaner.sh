#!/usr/bin/env bash
# Unit tests for Cleaner.java - pure java, no android, no platform jar.
# Run:  tests/cleaner.sh      (from anywhere)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${TMPDIR:?set TMPDIR}/ztrackpad-cleaner-test"
rm -rf "$BUILD"; mkdir -p "$BUILD"
command -v java >/dev/null || { echo "no java"; exit 1; }

javac -d "$BUILD" "$ROOT/java/app/so7o/ztrackpad/Cleaner.java" "$ROOT/tests/CleanerTest.java" 2>&1
java -cp "$BUILD" app.so7o.ztrackpad.CleanerTest
