#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$ROOT"

required_paths=(
  verification/permission/fixtures
  verification/plugins/phase-observer
  verification/integration/quasiquotes-symbol-info
  scripts/verify-macroparadise-coexistence.sh
  scripts/verify-zinc-lifecycle.sh
  scripts/verify-persistent-build-lifecycle.sh
  scripts/build_lifecycle_driver.py
  scripts/verify-quasiquotes-integration.sh
  scripts/verify-isolated-publish-local.sh
)

for path in "${required_paths[@]}"; do
  if [[ ! -e "$path" ]]; then
    printf 'PUBLIC BOUNDARY FAIL: missing capability-oriented path %s\n' "$path" >&2
    exit 1
  fi
done

forbidden_paths=(
  m0-fixtures
  m4a-observer
  m6
  scripts/verify-m4b-macroparadise.sh
  scripts/verify-m4c-macroparadise.sh
  scripts/verify-m5a.sh
  scripts/verify-m5a-zinc.sh
  scripts/verify-m5b.sh
  scripts/verify-m5b-zinc.sh
  scripts/verify-m5b-zinc-lane.sh
  scripts/verify-m5c.sh
  scripts/verify-m5c-lane.sh
  scripts/m5c_driver.py
  scripts/verify-m6-quasiquotes.sh
  scripts/verify-m7a-isolated-publication.sh
  scripts/verify-release-candidate.sh
)

for path in "${forbidden_paths[@]}"; do
  if [[ -e "$path" ]]; then
    printf 'PUBLIC BOUNDARY FAIL: controller chronology remains in public path %s\n' "$path" >&2
    exit 1
  fi
done

if rg -n -i '\bM(0|1|3|4A|4B|4C|5A|5B|5C|6|7A)\b|Prompt[ _-]*0*[0-9]+|controller review|publication remains unauthorized' README.md; then
  echo 'PUBLIC BOUNDARY FAIL: README contains controller chronology' >&2
  exit 1
fi

if rg -n '\bverifyM(0|1|3|4A|4B|4C|5A|5B|5C|6)\b' build.sbt; then
  echo 'PUBLIC BOUNDARY FAIL: milestone-named public sbt task remains' >&2
  exit 1
fi

if rg -n 'allow-experimental M[01]|@allowExperimental M[01]' plugin/src/main; then
  echo 'PUBLIC BOUNDARY FAIL: milestone wording remains in a user diagnostic' >&2
  exit 1
fi

if find . -path './target' -prune -o -path './.git' -prune -o -type f \
    \( -name 'STATE.md' -o -name 'ROADMAP.md' -o -name '*.prompt.md' \) -print | grep -q .; then
  echo 'PUBLIC BOUNDARY FAIL: controller-only file copied into product' >&2
  exit 1
fi

printf '%s\n' 'Public product boundary: PASS'
