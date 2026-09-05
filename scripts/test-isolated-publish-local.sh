#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
VERIFY="$ROOT/scripts/verify-isolated-publish-local.sh"

test -f "$VERIFY"
bash -n "$VERIFY"

required_tokens=(
  'VERSION=0.1.0-SNAPSHOT'
  'ORGANIZATION=com.github.dmytromitin'
  'publishLocal'
  '++3.3.8!'
  '++3.8.4!'
  '++3.9.0!'
  'allow-experimental-annotation_3'
  'module="allow-experimental-plugin_$lane"'
  'SBT_OPTS="$isolated_sbt_opts"'
  'META-INF/LICENSE'
  '<scope>provided</scope>'
  'CrossVersion.full'
  '% Provided'
)

for token in "${required_tokens[@]}"; do
  grep -Fq "$token" "$VERIFY" || {
    printf 'ISOLATED PUBLISHLOCAL CONTRACT FAIL: missing %s\n' "$token" >&2
    exit 1
  }
done

if rg -n '0\.1\.0-M7A-LOCAL|ORGANIZATION=io\.github\.dmytromitin|publishTo|\.ivy2/local|\.m2/repository|verify-release-candidate|[[:space:]]-experimental([^[:alnum:]-]|$)' "$VERIFY"; then
  echo 'ISOLATED PUBLISHLOCAL CONTRACT FAIL: obsolete, remote, user-cache, secret, or widened-authority path present' >&2
  exit 1
fi

if rg -n 'central\.sonatype\.com|s01\.oss\.sonatype\.org|oss\.sonatype\.org' "$VERIFY"; then
  echo 'ISOLATED PUBLISHLOCAL CONTRACT FAIL: remote publication endpoint present' >&2
  exit 1
fi

printf '%s\n' 'Isolated publishLocal structural contract: PASS'
