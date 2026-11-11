#!/usr/bin/env bash
# Builds Habits with the repo's shared ../build.sh (tools, packaging and signing key are shared).
# Output: build/Habits.apk. For a new version raise VERSION_CODE here.
PROJECT=$(cd "$(dirname "$0")" && pwd) NAME=Habits VERSION_CODE=8 VERSION_NAME=2.3 \
    exec "$(dirname "$0")/../build.sh"
