#!/usr/bin/env bash
# Runs the Gym Notes tests: builds the app (the tests read its resources and R.java from gym/build),
# then runs gym/test with Maven. Extra arguments go to Maven, for example
#   gym/test.sh -Dtest=CoachTest       one class
#   gym/test.sh -Dshots=/tmp/shots     also write screenshots of the UI flow there
# Requires: JDK 17+, Maven, python3, curl.
set -euo pipefail
cd "$(dirname "$0")/.."
bash gym/build.sh
status=0
mvn -B -q -f gym/test/pom.xml test "$@" || status=$?
grep -h "Tests run" gym/test/target/surefire-reports/*.txt || true
exit $status
