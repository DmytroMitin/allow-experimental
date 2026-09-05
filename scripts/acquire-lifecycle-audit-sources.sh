#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo 'Usage: bash scripts/acquire-lifecycle-audit-sources.sh' >&2
  exit 2
fi

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
COURSIER_CACHE="${COURSIER_CACHE:-$ROOT/target/verification-tool-state/coursier-cache}"
EVIDENCE="$ROOT/target/verification-tool-state/lifecycle-sources"
export COURSIER_CACHE
mkdir -p -- "$COURSIER_CACHE" "$EVIDENCE"

for lane in 3.3.8 3.8.4 3.9.0; do
  cs fetch --classifier sources "org.scala-lang:scala3-compiler_3:$lane" \
    > "$EVIDENCE/scala3-compiler-sources-$lane.txt"
  cs fetch --classifier sources "org.scala-lang:scala3-sbt-bridge:$lane" \
    > "$EVIDENCE/scala3-sbt-bridge-sources-$lane.txt"
done

printf '%s\n' 'Lifecycle compiler and sbt-bridge sources acquired: PASS'
