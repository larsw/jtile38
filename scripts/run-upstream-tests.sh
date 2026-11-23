#!/usr/bin/env bash
# Runs Tile38's own integration test suite (tests/ in tidwall/tile38) against an external server,
# by default the jtile38 server jar. Each sub-test gets a fresh server process with its own port and
# data directory, exactly like the upstream harness does in-process.
#
# Usage: scripts/run-upstream-tests.sh [go test -run filter, e.g. 'TestIntegration/keys']
# Environment:
#   TILE38_SRC              upstream checkout to use (default: target/tile38-upstream, fetched)
#   TILE38_EXTERNAL_SERVER  server command (default: java -jar jtile38-server/target/jtile38-server.jar)
#   PRINTLOG=1              show server output
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SHA=34f86375d07fa89d90a4afb5132a8e35af1583e4 # tidwall/tile38 master, 2026-11-12
SRC=${TILE38_SRC:-$ROOT/target/tile38-upstream}
FILTER=${1:-TestIntegration}
JAR=$ROOT/jtile38-server/target/jtile38-server.jar
LOG=$ROOT/target/upstream-tests.log

if [ ! -d "$SRC/.git" ]; then
  echo "Fetching tidwall/tile38@$SHA into $SRC"
  mkdir -p "$SRC"
  git -C "$SRC" init -q
  git -C "$SRC" fetch -q --depth 1 https://github.com/tidwall/tile38 "$SHA"
  git -C "$SRC" checkout -q FETCH_HEAD
fi
git -C "$SRC" checkout -q -- tests
git -C "$SRC" apply "$ROOT/compat/upstream-tests/external-server.patch"

if [ -z "${TILE38_EXTERNAL_SERVER:-}" ]; then
  if [ ! -f "$JAR" ]; then
    (cd "$ROOT" && mvn -B -q -pl jtile38-server -am package -DskipTests)
  fi
  TILE38_EXTERNAL_SERVER="java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xmx256m -jar $JAR"
fi
export TILE38_EXTERNAL_SERVER
echo "Server: $TILE38_EXTERNAL_SERVER"

mkdir -p "$(dirname "$LOG")"
(cd "$SRC" && go test -count=1 -timeout 30m -run "$FILTER" ./tests) >"$LOG" 2>&1
# strip colors, then summarize the per-test result lines
sed -E 's/\x1b\[[0-9;]*m//g' "$LOG" >"$LOG.txt"
grep -E '^Testing |\[(ok|fail)\]$' "$LOG.txt"
ok=$(grep -c '\[ok\]$' "$LOG.txt")
fail=$(grep -c '\[fail\]$' "$LOG.txt")
echo
echo "passed: $ok, failed: $fail (details in $LOG.txt)"

# Compare with the known failures.
EXPECTED=$ROOT/compat/upstream-tests/expected-failures.txt
failing=$(grep -oE 'tests_test.go:[0-9]+: TestIntegration/.*' "$LOG.txt" | sed -E 's/^tests_test.go:[0-9]+: TestIntegration\///' | sort -u)
expected=$(sed -E 's/#.*//; s/[[:space:]]+$//' "$EXPECTED" | grep -v '^$' | sort -u)
ran=$(grep -oE '^Testing [a-z0-9]+' "$LOG.txt" | sed 's/^Testing //')
regressions=$(comm -23 <(echo "$failing") <(echo "$expected") | grep -v '^$' || true)
fixed=$(comm -13 <(echo "$failing") <(echo "$expected") | grep -v '^$' | while read -r t; do
  echo "$ran" | grep -qx "${t%%/*}" && echo "$t"; done || true)
if [ -n "$fixed" ]; then
  echo
  echo "Now passing (remove from $EXPECTED):"
  echo "$fixed" | sed 's/^/  /'
fi
if [ -n "$regressions" ]; then
  echo
  echo "REGRESSIONS (failing but not in $EXPECTED):"
  echo "$regressions" | sed 's/^/  /'
  exit 1
fi
if ! grep -qE '^(ok|FAIL)[[:space:]]' "$LOG.txt"; then
  echo "go test did not finish; see $LOG.txt"
  exit 1
fi
echo "No regressions."
