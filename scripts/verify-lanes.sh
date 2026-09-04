#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo "Usage: bash scripts/verify-lanes.sh (qualifies exact 3.3.8, 3.8.4 and 3.9.0)" >&2
  exit 2
fi

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
mkdir -p target/verification-logs

# Separate processes prevent the active compiler/classpath from carrying over.
# Each project's target and clean boundary are also exact-lane-specific.
qualified_lanes=()

verify_preserved() {
  local earlier="$1"
  sha256sum --check "target/verification-logs/$earlier-preservation.sha256"
  echo "ISOLATION PASS [$earlier] earlier-lane artifacts and representative qualified evidence preserved"
}

for lane in 3.3.8 3.8.4 3.9.0; do
  for earlier in "${qualified_lanes[@]}"; do
    verify_preserved "$earlier"
  done

  gates=(clean verifyLane verifyM0 verifyM1 verifyM3)
  if [[ "$lane" == "3.9.0" ]]; then
    gates+=(verifyM4A)
  fi

  sbt -batch "++$lane" "${gates[@]}" \
    2>&1 | tee "target/verification-logs/$lane.log"

  for earlier in "${qualified_lanes[@]}"; do
    verify_preserved "$earlier"
  done

  evidence=(
    "annotation/target/scala-$lane/allow-experimental-annotation_3-0.1.0-M0-SNAPSHOT.jar"
    "plugin/target/scala-$lane/allow-experimental-plugin_3-0.1.0-M0-SNAPSHOT.jar"
    "target/scala-$lane/m0-verification/provider/m0/Library\$package.tasty"
    "target/scala-$lane/m1-verification/positive/classes/m1/Library\$package.tasty"
    "target/scala-$lane/m1-verification/positive/compiler.log"
    "target/scala-$lane/m3-verification/producer-with-permission/classes/m3/MacroApi.tasty"
    "target/scala-$lane/m3-verification/producer-with-permission/compiler.log"
    "target/scala-$lane/m3-verification/downstream/javap.log"
    "target/scala-$lane/m3-verification/serialized-inline-authority.txt"
  )
  if [[ "$lane" == "3.9.0" ]]; then
    evidence+=(
      "target/scala-$lane/m4a-verification/summary.txt"
      "target/scala-$lane/m4a-verification/harmless-positive/observer.log"
      "target/scala-$lane/m4a-verification/allow-first-p1/compiler.log"
      "target/scala-$lane/m4a-verification/observer-first-p2/compiler.log"
    )
  fi
  sha256sum "${evidence[@]}" > "target/verification-logs/$lane-preservation.sha256"
  verify_preserved "$lane"
  qualified_lanes+=("$lane")
done

for lane in "${qualified_lanes[@]}"; do
  verify_preserved "$lane"
done
