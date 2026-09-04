#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo "Usage: bash scripts/verify-m5a.sh" >&2
  exit 2
fi

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
mkdir -p target/verification-logs

peer=../macroparadise-scala3
peer_head_before=$(git -C "$peer" rev-parse HEAD)
peer_status_before=$(git -C "$peer" status --porcelain=v1)

for lane in 3.3.8 3.8.4; do
  sbt -batch "++$lane" clean verifyLane verifyM0 verifyM1 verifyM3 verifyM4C \
    2>&1 | tee "target/verification-logs/m5a-$lane.log"
done

sbt -batch '++3.9.0' clean verifyLane verifyM0 verifyM1 verifyM3 verifyM4A verifyM4B verifyM5ASameJvm \
  2>&1 | tee target/verification-logs/m5a-3.9.0.log

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
  'READ_ONLY_PEERS_BUILT_FOR_M5A=NO' \
  > target/verification-logs/m5a-regression-summary.txt

bash scripts/verify-m5a-zinc.sh \
  2>&1 | tee target/verification-logs/m5a-zinc.log

sbt -batch '++3.9.0' verifyM5A \
  2>&1 | tee target/verification-logs/m5a-final.log

sha256sum \
  target/scala-3.9.0/m5a-verification/same-jvm-summary.txt \
  target/scala-3.9.0/m5a-verification/zinc/zinc-summary.txt \
  target/scala-3.9.0/m5a-verification/zinc/zinc-evidence.sha256 \
  target/scala-3.9.0/m5a-verification/summary.txt \
  target/verification-logs/m5a-regression-summary.txt \
  > target/verification-logs/m5a-final-evidence.sha256

sha256sum --check target/verification-logs/m5a-final-evidence.sha256
