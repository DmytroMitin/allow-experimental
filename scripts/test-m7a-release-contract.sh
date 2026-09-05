#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
ISOLATED="$ROOT/scripts/verify-m7a-isolated-publication.sh"
FULL="$ROOT/scripts/verify-release-candidate.sh"

for script in "$ISOLATED" "$FULL"; do
  test -f "$script"
  bash -n "$script"
done

grep -Fq '0.1.0-M7A-LOCAL' "$ISOLATED"
grep -Fq 'allow-experimental-annotation_3' "$ISOLATED"
grep -Fq 'for lane in 3.3.8 3.8.4 3.9.0' "$ISOLATED"
grep -Fq 'module="allow-experimental-plugin_$lane"' "$ISOLATED"
grep -Fq 'SBT_OPTS="$m7a_sbt_opts"' "$ISOLATED"

if rg -n '(^|[^[:alnum:]])publishLocal([^[:alnum:]]|$)|\.ivy2/local|\.m2/repository|[[:space:]]-experimental([^[:alnum:]-]|$)' \
    "$ISOLATED" "$FULL"; then
  echo 'M7A contract test: forbidden publication or authority path present' >&2
  exit 1
fi

if rg -n 'central\.sonatype\.com|repo1\.maven\.org|s01\.oss\.sonatype\.org|oss\.sonatype\.org' \
    "$ISOLATED" "$FULL"; then
  echo 'M7A contract test: remote publication endpoint present' >&2
  exit 1
fi

grep -Fq 'verify-m4b-macroparadise.sh' "$FULL"
grep -Fq 'verify-m4c-macroparadise.sh' "$FULL"
grep -Fq 'verify-m5a.sh' "$FULL"
grep -Fq 'verify-m5c.sh' "$FULL"
grep -Fq 'verify-m6-quasiquotes.sh' "$FULL"
grep -Fq 'verify-m7a-isolated-publication.sh' "$FULL"
grep -Fq 'org.scala-lang:scala3-sbt-bridge:$lane' "$FULL"
grep -Fq -- '-Dsbt.boot.directory=$STATE/boot' "$FULL"
grep -Fq -- '-Dsbt.boot.directory=$fixture/.sbt-global/boot' "$ROOT/scripts/verify-m5a-zinc.sh"
grep -Fq -- '-Dsbt.boot.directory=$fixture/.sbt-global/boot' "$ROOT/scripts/verify-m5b-zinc-lane.sh"
grep -Fq 'SBT_OPTS="$fixture_sbt_opts"' "$ROOT/scripts/verify-m5a-zinc.sh"
grep -Fq 'SBT_OPTS="$fixture_sbt_opts"' "$ROOT/scripts/verify-m5b-zinc-lane.sh"

printf '%s\n' 'M7A release contract structural test: PASS'
