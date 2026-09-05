#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
ACQUIRE="$ROOT/scripts/acquire-lifecycle-audit-sources.sh"
ENTRY="$ROOT/scripts/verify-zinc-lifecycle.sh"
FIXTURE39="$ROOT/scripts/verify-zinc-lifecycle-3.9.0-fixture.sh"
FIXTURE_OLDER="$ROOT/scripts/verify-zinc-lifecycle-lane.sh"

test -f "$ACQUIRE" || {
  echo 'LIFECYCLE SOURCE ISOLATION FAIL: acquisition helper is missing' >&2
  exit 1
}

for script in "$ACQUIRE" "$ENTRY" "$FIXTURE39" "$FIXTURE_OLDER"; do
  bash -n "$script"
done

grep -Fq 'org.scala-lang:scala3-compiler_3:$lane' "$ACQUIRE"
grep -Fq 'org.scala-lang:scala3-sbt-bridge:$lane' "$ACQUIRE"
grep -Fq 'COURSIER_CACHE="${COURSIER_CACHE:-$ROOT/target/verification-tool-state/coursier-cache}"' "$ACQUIRE"
grep -Fq 'bash "$root/scripts/acquire-lifecycle-audit-sources.sh"' "$ENTRY"
grep -Fq 'source_cache=${COURSIER_CACHE:-$HOME/.cache/coursier}' "$FIXTURE_OLDER"
grep -Fq 'find "$source_cache"' "$FIXTURE_OLDER"

for script in "$FIXTURE39" "$FIXTURE_OLDER"; do
  grep -Fq -- '-Dsbt.boot.directory=$fixture/.sbt-global/boot' "$script"
  grep -Fq 'SBT_OPTS="$fixture_sbt_opts"' "$script"
done

printf '%s\n' 'Lifecycle source and nested boot isolation: PASS'
