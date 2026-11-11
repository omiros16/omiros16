#!/usr/bin/env bash
# Robolectric tests for Habits, under libfaketime (apt: faketime) so each test sets the phone's clock.
#   ./run.sh                       all tests
#   ./run.sh -Dtest=CoreTest       one class
# Screenshots land in out/.
set -uo pipefail
S=$(cd "$(dirname "$0")" && pwd)
LIB=/usr/lib/x86_64-linux-gnu/faketime/libfaketimeMT.so.1
[ -f "$LIB" ] || { echo "needs libfaketime: apt-get install faketime"; exit 1; }
# Tests run against the current code: build it, then hand its classes to Maven.
out=$("$S/../build.sh" 2>&1) || { echo "$out"; exit 1; }
(cd "$S/../build/classes" && jar cf "$S/app.jar" .)
# Download and compile on the real clock: under the fake one, TLS certificates can look not yet valid.
cd "$S"
mvn -q -B test-compile 2>&1 | grep -v JAVA_TOOL_OPTIONS | head -30
for a in surefire-junit4 common-junit4; do
    mvn -q -B dependency:get -Dartifact=org.apache.maven.surefire:$a:3.2.5 2>&1 | grep -v JAVA_TOOL_OPTIONS | head -5
done
rm -rf "$S/target/surefire-reports"
echo "@2026-10-17 21:00:00" > "$S/faketime.txt"
LD_PRELOAD=$LIB FAKETIME_TIMESTAMP_FILE=$S/faketime.txt FAKETIME_NO_CACHE=1 FAKETIME_DONT_FAKE_MONOTONIC=1 \
  mvn -q -B test -Dshots.out="$S/out" "$@" 2>&1 | grep -v JAVA_TOOL_OPTIONS | grep -v 'Invalid ID' \
  | grep -vE '^\s+at (org.junit|org.apache|java.base|com.google|jdk.proxy|jdk.internal|org.robolectric)' | head -60
grep -h 'Tests run' "$S"/target/surefire-reports/*.txt 2>/dev/null
