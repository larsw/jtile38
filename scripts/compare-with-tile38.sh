#!/usr/bin/env bash
# Runs the same tile38-cli session against two servers (e.g. the original Go Tile38 and jtile38)
# and diffs the output with "elapsed" timings removed.
#
# Usage: scripts/compare-with-tile38.sh <tile38-cli> <reference-port> <jtile38-port>
# Both servers must be running and are FLUSHDB'd by this script.
set -euo pipefail
CLI=$1
REF_PORT=$2
JVM_PORT=$3

session() {
  local p=$1
  c() { "$CLI" -p "$p" "$@"; }
  c flushdb >/dev/null
  c set fleet truck1 point 33.5123 -112.2693
  c set fleet truck2 field speed 55 point 33.4626 -112.1695
  c set fleet truck3 field speed 1e3 field name '"bob"' point 33.47 -112.20 12.5
  c set areas a object '{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-112.3,33.4],[-112.1,33.4],[-112.1,33.6],[-112.3,33.6],[-112.3,33.4]]]},"properties":{"name":"x"}}'
  c get fleet truck3 withfields; c get fleet truck3 point; c get fleet truck2 hash 7; c get fleet truck2 bounds
  c nearby fleet distance point 33.462 -112.268 6000; c nearby fleet limit 2 ids point 33.462 -112.268
  c within fleet points get areas a; c intersects fleet bounds tile 1 1 1; c intersects fleet hashes 5 quadkey 0231
  c scan fleet where speed 50 2000 ids; c scan fleet match 'truck[12]' desc; c bounds fleet; c stats fleet nokey
  c get areas a; c ttl fleet truck1; c type fleet; c get nokey x; c set fleet x point a b; c fset fleet truck1 xx speed 0
  c --resp nearby fleet distance point 33.462 -112.268 6000; c --resp get fleet truck3 withfields point; c --resp scan fleet ids
}

ref=$(mktemp)
jvm=$(mktemp)
session "$REF_PORT" | sed -E 's/"elapsed":"[^"]*"//' >"$ref"
session "$JVM_PORT" | sed -E 's/"elapsed":"[^"]*"//' >"$jvm"
if diff "$ref" "$jvm"; then
  echo "IDENTICAL ($(wc -l <"$ref") lines)"
fi
rm -f "$ref" "$jvm"
