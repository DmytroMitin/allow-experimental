#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo "Usage: bash scripts/verify-macroparadise-coexistence.sh" >&2
  exit 2
fi

PRODUCT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
# shellcheck source=macroparadise-toolchain.sh
source "$PRODUCT_ROOT/scripts/macroparadise-toolchain.sh"
PEER_ROOT="$PRODUCT_ROOT/../macroparadise-scala3"
PIN=d773332c29efce90b3af343d34ae5450a93f6d93
DISPOSABLE_ROOT="$PRODUCT_ROOT/target/m4c-verification"
M4B_DISPOSABLE="$PRODUCT_ROOT/target/m4b-verification/macroparadise-disposable"
LOG_ROOT="$PRODUCT_ROOT/target/verification-logs"
PEER_HEAD_BEFORE=$(git -C "$PEER_ROOT" rev-parse HEAD)
PEER_STATUS_BEFORE=$(git -C "$PEER_ROOT" status --porcelain=v1)
PEER_STATUS_SHA256_BEFORE=$(printf '%s' "$PEER_STATUS_BEFORE" | sha256sum | cut -d ' ' -f 1)

mkdir -p "$DISPOSABLE_ROOT" "$LOG_ROOT"
git -C "$PEER_ROOT" cat-file -e "$PIN^{commit}"

qualified_lanes=()

verify_preserved() {
  local lane="$1"
  sha256sum --check "$LOG_ROOT/m4c-$lane-preservation.sha256"
  echo "M4C ISOLATION PASS [$lane] exact-lane peer/product artifacts and evidence preserved"
}

materialize_peer_lane() {
  local lane="$1"
  local disposable="$2"
  if test ! -d "$disposable/.git"; then
    git clone --no-hardlinks --no-checkout "$PEER_ROOT" "$disposable"
    git -C "$disposable" checkout --detach "$PIN"
  fi
  test "$(git -C "$disposable" rev-parse HEAD)" = "$PIN"
  test -z "$(git -C "$disposable" status --porcelain=v1)"
  git -C "$disposable" diff --quiet "$PIN" --
  git -C "$disposable" diff --cached --quiet
  test ! -e "$disposable/.git/objects/info/alternates"

  local comparable=0
  while IFS= read -r object_path; do
    local source_object="$PEER_ROOT/.git/objects/$object_path"
    local copied_object="$disposable/.git/objects/$object_path"
    if test -f "$source_object"; then
      test "$(stat -c '%d:%i' "$source_object")" != "$(stat -c '%d:%i' "$copied_object")"
      comparable=$((comparable + 1))
    fi
  done < <(find "$disposable/.git/objects" -type f -printf '%P\n')
  test "$comparable" -gt 0
  printf 'M4C independent Git object comparisons [%s]: %s\n' "$lane" "$comparable"
  m4c_check_peer_toolchain "$disposable"
}

record_preservation() {
  local lane="$1"
  local peer_api="$DISPOSABLE_ROOT/macroparadise-$lane/plugin-api/target/scala-$lane/macroparadise-scala3-plugin-api_$lane-0.1.1-SNAPSHOT.jar"
  local peer_plugin="$DISPOSABLE_ROOT/macroparadise-$lane/plugin/target/scala-$lane/macroparadise-scala3-plugin_$lane-0.1.1-SNAPSHOT.jar"
  sha256sum \
    "$peer_api" \
    "$peer_plugin" \
    "$PRODUCT_ROOT/annotation/target/scala-$lane/allow-experimental-annotation_3-0.1.0-SNAPSHOT.jar" \
    "$PRODUCT_ROOT/plugin/target/scala-$lane/allow-experimental-plugin_$lane-0.1.0-SNAPSHOT.jar" \
    "$PRODUCT_ROOT/target/scala-$lane/m4c-verification/summary.txt" \
    "$PRODUCT_ROOT/target/scala-$lane/m4c-verification/peer-build.txt" \
    "$PRODUCT_ROOT/target/scala-$lane/m4c-verification/source-hashes.txt" \
    "$PRODUCT_ROOT/target/scala-$lane/m4c-verification/phase-plan-allow-first/observer.log" \
    "$PRODUCT_ROOT/target/scala-$lane/m4c-verification/phase-plan-macroparadise-first/observer.log" \
    > "$LOG_ROOT/m4c-$lane-preservation.sha256"
  verify_preserved "$lane"
}

for lane in 3.3.8 3.8.4; do
  for earlier in "${qualified_lanes[@]}"; do
    verify_preserved "$earlier"
  done

  materialize_peer_lane "$lane" "$DISPOSABLE_ROOT/macroparadise-$lane"
  (
    cd "$DISPOSABLE_ROOT/macroparadise-$lane"
    sbt -Dmacroparadise.exactScalaVersion="$lane" -batch \
      "++$lane!" clean 'pluginApi/packageBin' 'plugin/packageBin'
  ) 2>&1 | tee "$LOG_ROOT/m4c-macroparadise-build-$lane.log"

  (
    cd "$PRODUCT_ROOT"
    sbt -batch "++$lane" clean verifyLane verifyPermissionFixtures verifyPermissionScope verifyMacroImplementation verifyMacroParadiseCoexistence
  ) 2>&1 | tee "$LOG_ROOT/m4c-full-gate-$lane.log"

  record_preservation "$lane"
  qualified_lanes+=("$lane")
done

for lane in "${qualified_lanes[@]}"; do
  verify_preserved "$lane"
done

peer_plugin_338="$DISPOSABLE_ROOT/macroparadise-3.3.8/plugin/target/scala-3.3.8/macroparadise-scala3-plugin_3.3.8-0.1.1-SNAPSHOT.jar"
peer_plugin_384="$DISPOSABLE_ROOT/macroparadise-3.8.4/plugin/target/scala-3.8.4/macroparadise-scala3-plugin_3.8.4-0.1.1-SNAPSHOT.jar"
test "$(sha256sum "$peer_plugin_338" | cut -d ' ' -f 1)" != \
  "$(sha256sum "$peer_plugin_384" | cut -d ' ' -f 1)"

mkdir -p "$(dirname "$M4B_DISPOSABLE")"
materialize_peer_lane 3.9.0 "$M4B_DISPOSABLE"
(
  cd "$M4B_DISPOSABLE"
  sbt -Dmacroparadise.exactScalaVersion=3.9.0 -batch \
    '++3.9.0!' clean 'pluginApi/packageBin' 'plugin/packageBin'
) 2>&1 | tee "$LOG_ROOT/m4b-macroparadise-build-3.9.0.log"

(
  cd "$PRODUCT_ROOT"
  sbt -batch '++3.9.0' clean verifyLane verifyPermissionFixtures verifyPermissionScope verifyMacroImplementation verifyPhaseObserverCoexistence verifyMacroParadiseCoexistence39
) 2>&1 | tee "$LOG_ROOT/m4c-retained-full-gate-3.9.0.log"

for lane in "${qualified_lanes[@]}"; do
  verify_preserved "$lane"
done

PEER_HEAD_AFTER=$(git -C "$PEER_ROOT" rev-parse HEAD)
PEER_STATUS_AFTER=$(git -C "$PEER_ROOT" status --porcelain=v1)
PEER_STATUS_SHA256_AFTER=$(printf '%s' "$PEER_STATUS_AFTER" | sha256sum | cut -d ' ' -f 1)
test "$PEER_HEAD_AFTER" = "$PEER_HEAD_BEFORE"
test "$PEER_STATUS_AFTER" = "$PEER_STATUS_BEFORE"

printf '%s\n' \
  "LOCAL_PEER_HEAD_BEFORE=$PEER_HEAD_BEFORE" \
  "LOCAL_PEER_HEAD_AFTER=$PEER_HEAD_AFTER" \
  "LOCAL_PEER_STATUS_SHA256_BEFORE=$PEER_STATUS_SHA256_BEFORE" \
  "LOCAL_PEER_STATUS_SHA256_AFTER=$PEER_STATUS_SHA256_AFTER" \
  'PINNED_PEER_BYTES_FROM_DIRTY_WORKTREE=NO' \
  'PEER_CHECKOUT_MUTATED=NO' \
  > "$LOG_ROOT/m4c-peer-state.txt"

printf '%s\n' \
  'PROMPT_011_M4C=PASS' \
  "MACROPARADISE_PINNED_SHA=$PIN" \
  'SCALA_3_3_8_M4C=PASS' \
  'SCALA_3_8_4_M4C=PASS' \
  'SCALA_3_9_0_M4B_RETAINED=PASS' \
  'REAL_MACROPARADISE_COEXISTENCE_3_3_8=COMPATIBLE_PASS' \
  'REAL_MACROPARADISE_COEXISTENCE_3_8_4=COMPATIBLE_PASS' \
  'REAL_MACROPARADISE_COEXISTENCE_3_9_0=COMPATIBLE_PASS' \
  'GENERIC_SENSITIVE_WINDOW_PROTECTION_3_3_8=UNQUALIFIED' \
  'GENERIC_SENSITIVE_WINDOW_PROTECTION_3_8_4=UNQUALIFIED' \
  'GENERIC_SENSITIVE_WINDOW_PROTECTION_3_9_0=FAIL_CLOSED_PASS' \
  'REAL_MACROPARADISE_COEXISTENCE_ALL_THREE=PASS' \
  'M4_IMPLEMENTATION_EVIDENCE_COMPLETE=YES' \
  'M4C_CONTROLLER_RECOMMENDATION=ACCEPT_WITH_QUALIFICATIONS' \
  'M4_CONTROLLER_RECOMMENDATION=ACCEPT_WITH_QUALIFICATIONS' \
  'PEER_REPOSITORIES_MODIFIED=NO' \
  'RELEASE_AUTHORIZED=NO' \
  > "$LOG_ROOT/m4c-final-summary.txt"

(
  cd "$PRODUCT_ROOT"
  sha256sum \
    target/verification-logs/m4c-macroparadise-build-3.3.8.log \
    target/verification-logs/m4c-macroparadise-build-3.8.4.log \
    target/verification-logs/m4b-macroparadise-build-3.9.0.log \
    target/verification-logs/m4c-full-gate-3.3.8.log \
    target/verification-logs/m4c-full-gate-3.8.4.log \
    target/verification-logs/m4c-retained-full-gate-3.9.0.log \
    target/verification-logs/m4c-peer-state.txt \
    target/verification-logs/m4c-final-summary.txt \
    > target/verification-logs/m4c-final.sha256
)

printf '%s\n' 'M4C exact Scala 3.3.8/3.8.4 plus retained 3.9.0 full gate: PASS'
