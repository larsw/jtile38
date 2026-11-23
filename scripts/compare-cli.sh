#!/usr/bin/env bash
# Runs the same sessions through the original tile38-cli and jtile38-cli against one server and
# diffs stdout and stderr (with "elapsed" timings removed).
#
# Usage: scripts/compare-cli.sh <tile38-cli> <jtile38-cli.jar> <port>
set -uo pipefail
GO_CLI=$1
JAR=$2
PORT=$3

session() {
  local cli=("$@")
  c() { "${cli[@]}" -p "$PORT" "$@" 2>&1; }
  c flushdb
  c set fleet truck1 field speed 55 point 33.5123 -112.2693
  c set fleet truck2 point 33.4626 -112.1695 12
  c set fleet truck3 string 'hello world'
  c get fleet truck1 withfields
  c --resp get fleet truck1 withfields
  c --resp scan fleet
  c --resp nearby fleet distance point 33.462 -112.268 6000
  c --raw --resp get fleet truck2 point
  c --raw get fleet truck2
  c get fleet nope
  c --resp get fleet nope
  c --resp del fleet nope
  c --resp keys '*'
  c --resp ttl fleet truck1
  c --resp bogus command
  c help get
  c help
  echo '{"type":"Point","coordinates":[1,2]}' | c -x set areas a object
  c get areas a
  printf 'get fleet truck1\nget fleet nope\n\noutput resp\nget fleet truck1 point\nbounds fleet\nquit\n' | c --noprompt
  c -p 1 ping
}

go_out=$(mktemp)
jvm_out=$(mktemp)
session "$GO_CLI" | sed -E 's/"elapsed":"[^"]*"//' >"$go_out"
session java -jar "$JAR" | sed -E 's/"elapsed":"[^"]*"//' >"$jvm_out"
if diff "$go_out" "$jvm_out"; then
  echo "IDENTICAL ($(wc -l <"$go_out") lines)"
fi
rm -f "$go_out" "$jvm_out"
