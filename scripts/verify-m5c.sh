#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo "Usage: bash scripts/verify-m5c.sh" >&2
  exit 2
fi

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
logs="$root/target/verification-logs"
mkdir -p -- "$logs"

peer_state() {
  find "$root/.." -maxdepth 1 -mindepth 1 -type d \
    \( -name 'macroparadise-*' -o -name 'quasiquotes-*' -o -name 'AUXify*' \) -print0 |
    sort -z |
    while IFS= read -r -d '' peer; do
      if [[ -d "$peer/.git" || -f "$peer/.git" ]]; then
        printf 'peer=%s\n' "$(basename -- "$peer")"
        git -C "$peer" rev-parse HEAD
        git -C "$peer" status --porcelain=v1
        printf 'end-peer\n'
      fi
    done
}

peer_before=$(mktemp "$logs/m5c-peers-before.XXXXXX")
peer_after=$(mktemp "$logs/m5c-peers-after.XXXXXX")
trap 'rm -f -- "$peer_before" "$peer_after"' EXIT
peer_state > "$peer_before"

# Retain the accepted core, real Macro-Paradise, same-JVM, batch Zinc,
# wrong-lane, and preservation gates without invoking a peer build.
bash "$root/scripts/verify-m5b.sh" 2>&1 | tee "$logs/m5c-retained-m5b.log"

for lane in 3.3.8 3.8.4 3.9.0; do
  bash "$root/scripts/verify-m5c-lane.sh" "$lane" 2>&1 | tee "$logs/m5c-$lane.log"
done

peer_state > "$peer_after"
cmp "$peer_before" "$peer_after"

printf '%s\n' \
  'M5A_M5B_RETAINED=PASS' \
  'WRONG_LANE_PLUGIN_PERMISSION_GRANTED=NO' \
  'M0_M1_M3_ALL_LANES_REGRESSION=PASS' \
  'M4_RETAINED_REGRESSION=PASS' \
  'LANE_ISOLATION_AND_PRESERVATION=PASS' \
  'PEER_REPOSITORIES_MODIFIED=NO' \
  'PEER_REPOSITORIES_BUILT_FOR_PROMPT_014=NO' \
  > "$logs/m5c-regression-summary.txt"

sbt -batch '++3.9.0' verifyM5C 2>&1 | tee "$logs/m5c-final.log"

sha256sum \
  "$root/target/scala-3.3.8/m5c-verification/persistent-sbt-summary.txt" \
  "$root/target/scala-3.3.8/m5c-verification/bsp-summary.txt" \
  "$root/target/scala-3.8.4/m5c-verification/persistent-sbt-summary.txt" \
  "$root/target/scala-3.8.4/m5c-verification/bsp-summary.txt" \
  "$root/target/scala-3.9.0/m5c-verification/persistent-sbt-summary.txt" \
  "$root/target/scala-3.9.0/m5c-verification/bsp-summary.txt" \
  "$root/target/scala-3.9.0/m5c-verification/summary.txt" \
  "$logs/m5c-regression-summary.txt" \
  > "$logs/m5c-final-evidence.sha256"
sha256sum --check "$logs/m5c-final-evidence.sha256"
