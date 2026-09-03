#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo "Usage: bash scripts/verify-lanes.sh (qualifies exact 3.8.4 and 3.9.0)" >&2
  exit 2
fi

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
mkdir -p target/verification-logs

# Separate processes prevent the active compiler/classpath from carrying over.
# Each project's target and clean boundary are also exact-lane-specific.
for lane in 3.8.4 3.9.0; do
  sbt -batch "++$lane" clean verifyLane verifyM0 verifyM1 \
    2>&1 | tee "target/verification-logs/$lane.log"
done
