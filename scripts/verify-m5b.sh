#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo "Usage: bash scripts/verify-m5b.sh" >&2
  exit 2
fi

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
logs="$root/target/verification-logs"
peer="$root/../macroparadise-scala3"
mkdir -p -- "$logs"

peer_head_before=$(git -C "$peer" rev-parse HEAD)
peer_status_before=$(git -C "$peer" status --porcelain=v1)

verify_preserved() {
  local manifest=$1
  if [[ -f "$manifest" ]]; then
    sha256sum --check "$manifest"
  fi
}

for lane in 3.3.8 3.8.4; do
  for prior in 3.3.8 3.8.4; do
    if [[ "$prior" != "$lane" ]]; then
      verify_preserved "$logs/m5b-$prior-preservation.sha256"
    fi
  done
  sbt -batch "++$lane" clean verifyLane verifyM0 verifyM1 verifyM3 verifyM4C verifyM5BSameJvm \
    2>&1 | tee "$logs/m5b-$lane.log"
  sha256sum \
    "$root/annotation/target/scala-$lane/allow-experimental-annotation_3-0.1.0-M0-SNAPSHOT.jar" \
    "$root/plugin/target/scala-$lane/allow-experimental-plugin_3-0.1.0-M0-SNAPSHOT.jar" \
    "$root/target/scala-$lane/m4c-verification/summary.txt" \
    "$root/target/scala-$lane/m5b-verification/same-jvm-summary.txt" \
    > "$logs/m5b-$lane-preservation.sha256"
done

verify_preserved "$logs/m5b-3.3.8-preservation.sha256"
verify_preserved "$logs/m5b-3.8.4-preservation.sha256"

sbt -batch '++3.9.0' clean verifyLane verifyM0 verifyM1 verifyM3 verifyM4A verifyM4B verifyM5ASameJvm \
  2>&1 | tee "$logs/m5b-3.9.0.log"

verify_preserved "$logs/m5b-3.3.8-preservation.sha256"
verify_preserved "$logs/m5b-3.8.4-preservation.sha256"

bash "$root/scripts/verify-m5a-zinc.sh" 2>&1 | tee "$logs/m5b-retained-m5a-zinc.log"
sbt -batch '++3.9.0' verifyM5A 2>&1 | tee "$logs/m5b-retained-m5a-final.log"

bash "$root/scripts/verify-m5b-zinc.sh" 2>&1 | tee "$logs/m5b-zinc.log"

verify_preserved "$logs/m5b-3.3.8-preservation.sha256"
verify_preserved "$logs/m5b-3.8.4-preservation.sha256"

test "$(git -C "$peer" rev-parse HEAD)" = "$peer_head_before"
test "$(git -C "$peer" status --porcelain=v1)" = "$peer_status_before"

printf '%s\n' \
  'SCALA_3_3_8_CORE=PASS' \
  'SCALA_3_8_4_CORE=PASS' \
  'SCALA_3_9_0_CORE=PASS' \
  'M4C_SCALA_3_3_8=PASS' \
  'M4C_SCALA_3_8_4=PASS' \
  'M4A_SCALA_3_9_0=PASS' \
  'M4B_SCALA_3_9_0=PASS' \
  'M5A_SCALA_3_9_0=PASS' \
  'LANE_ISOLATION_AND_PRESERVATION=PASS' \
  'READ_ONLY_PEERS_BUILT_FOR_PROMPT_013=NO' \
  > "$logs/m5b-regression-summary.txt"

sbt -batch '++3.9.0' verifyM5B 2>&1 | tee "$logs/m5b-final.log"

sha256sum \
  "$root/target/scala-3.3.8/m5b-verification/same-jvm-summary.txt" \
  "$root/target/scala-3.3.8/m5b-verification/zinc/zinc-summary.txt" \
  "$root/target/scala-3.8.4/m5b-verification/same-jvm-summary.txt" \
  "$root/target/scala-3.8.4/m5b-verification/zinc/zinc-summary.txt" \
  "$root/target/scala-3.9.0/m5a-verification/summary.txt" \
  "$root/target/scala-3.9.0/m5b-verification/summary.txt" \
  "$logs/m5b-regression-summary.txt" \
  > "$logs/m5b-final-evidence.sha256"
sha256sum --check "$logs/m5b-final-evidence.sha256"
