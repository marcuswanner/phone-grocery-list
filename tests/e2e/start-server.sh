#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TMP_DIR="$HERE/../../tmp"
mkdir -p "$TMP_DIR"
cd "$HERE/../../android"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
DATA_FILE="$TMP_DIR/groceries-e2e-${PORT:-38080}.json"
rm -f "$DATA_FILE"
exec ./gradlew -q :core:run --args="--port ${PORT:-38080} --host 127.0.0.1 --data $DATA_FILE"
