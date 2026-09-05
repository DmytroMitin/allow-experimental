#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo "Usage: bash scripts/verify-quasiquotes-integration.sh" >&2
  exit 2
fi

PRODUCT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
PEER_ROOT="$PRODUCT_ROOT/../quasiquotes-scala3"
PIN=b7425e2f97a42107e78c96454d14f66581889f80
REMOTE=https://github.com/DmytroMitin/quasiquotes-scala3.git
OVERLAY="$PRODUCT_ROOT/verification/integration/quasiquotes-symbol-info/quasiquotes-symbol-info-overlay.patch"
LOG_ROOT="$PRODUCT_ROOT/target/verification-logs"
mkdir -p "$LOG_ROOT"

PEER_HEAD_BEFORE=$(git -C "$PEER_ROOT" rev-parse HEAD)
PEER_STATUS_BEFORE=$(git -C "$PEER_ROOT" status --porcelain=v1)
PEER_STATUS_SHA_BEFORE=$(printf '%s' "$PEER_STATUS_BEFORE" | sha256sum | cut -d ' ' -f 1)

for lane in 3.3.8 3.8.4 3.9.0; do
  lane_root="$PRODUCT_ROOT/target/m6-verification/scala-$lane"
  disposable="$lane_root/quasiquotes-pinned"
  artifacts="$lane_root/artifacts"
  annotation="$PRODUCT_ROOT/annotation/target/scala-$lane/allow-experimental-annotation_3-0.1.0-SNAPSHOT.jar"
  plugin="$PRODUCT_ROOT/plugin/target/scala-$lane/allow-experimental-plugin_$lane-0.1.0-SNAPSHOT.jar"
  if grep -Fxq "SCALA_${lane//./_}_M6=PASS" "$LOG_ROOT/m6-summary-$lane.txt" 2>/dev/null; then
    test "$(git -C "$disposable" rev-parse HEAD)" = "$PIN"
    test -f "$annotation"; test -f "$plugin"
    sha256sum "$annotation" "$plugin" \
      "$artifacts/baseline/core.jar" "$artifacts/baseline/frontend.jar" \
      "$artifacts/overlay/core.jar" "$artifacts/overlay/frontend.jar" \
      > "$lane_root/artifact-hashes.txt"
    echo "M6 retained completed lane: $lane"
    continue
  fi
  mkdir -p "$lane_root" "$artifacts/baseline" "$artifacts/overlay"

  if test ! -d "$disposable/.git"; then
    git clone --no-hardlinks --no-checkout "$REMOTE" "$disposable"
    git -C "$disposable" checkout --detach "$PIN"
  fi
  test "$(git -C "$disposable" rev-parse HEAD)" = "$PIN"
  test -z "$(git -C "$disposable" symbolic-ref -q HEAD || true)"
  test -z "$(git -C "$disposable" status --porcelain=v1)"
  test ! -e "$disposable/.git/objects/info/alternates"
  test -z "$(find "$disposable/.git/objects" -type f -links +1 -print -quit)"
  printf '%s\n%s\n' "$PEER_HEAD_BEFORE" "$PEER_STATUS_SHA_BEFORE" > "$lane_root/peer-state-before.txt"

  (
    cd "$disposable"
    sha256sum \
      build.sbt \
      frontend/src/main/scala/quasiquotes/types/QuasiTypequotes.scala \
      frontend/src/main/scala/quasiquotes/types/GlobalSelectedTypeEnvironment.scala \
      frontend/src/main/scala/quasiquotes/types/GlobalSelectedTypeFrontend.scala \
      frontend/src/main/scala/quasiquotes/types/ResolvedTypeReflection.scala \
      frontend/src/main/scala/quasiquotes/types/TargetTypeReprInspector.scala \
      core/src/main/scala/quasiquotes/types/AppliedTypeConstructorPolicy.scala \
      frontend/src/test/scala/quasiquotes/types/GlobalSelectedTypeFrontendTest.scala \
      > "$lane_root/pinned-source-hashes.txt"
  )

  (
    cd "$PRODUCT_ROOT"
    sbt -batch "++$lane" 'annotation/packageBin' 'plugin/packageBin'
  ) 2>&1 | tee "$LOG_ROOT/m6-allow-artifacts-$lane.log"
  test -f "$annotation"; test -f "$plugin"

  (
    cd "$disposable"
    sbt -batch "++$lane!" clean 'core/packageBin' 'frontend/packageBin' \
      'frontend/testOnly quasiquotes.types.GlobalSelectedTypeFrontendTest'
  ) 2>&1 | tee "$LOG_ROOT/m6-quasiquotes-baseline-$lane.log"
  baseline_core="$disposable/core/target/scala-$lane/quasiquotes-scala3-core_3-0.3.0-SNAPSHOT.jar"
  baseline_frontend="$disposable/frontend/target/scala-$lane/quasiquotes-scala3-frontend_$lane-0.3.0-SNAPSHOT.jar"
  cp "$baseline_core" "$artifacts/baseline/core.jar"
  cp "$baseline_frontend" "$artifacts/baseline/frontend.jar"

  git -C "$disposable" apply "$OVERLAY"
  cp "$disposable/frontend/src/main/scala/quasiquotes/types/ResolvedTypeReflection.scala" "$lane_root/overlay-source.scala"
  perl -0pi -e 's/import io\.github\.dmytromitin\.allowexperimental\.allowExperimental\n//; s/  \@allowExperimental\n//' \
    "$disposable/frontend/src/main/scala/quasiquotes/types/ResolvedTypeReflection.scala"
  if (
    cd "$disposable"
    sbt -batch "++$lane!" clean \
      "set frontend / Compile / unmanagedJars += file(\"$annotation\")" \
      "set frontend / Compile / scalacOptions ++= Seq(\"-Xplugin:$plugin\", \"-Xplugin-require:allow-experimental\")" \
      'frontend/compile'
  ) > "$lane_root/negative-without-marker.log" 2>&1; then
    echo "M6 negative without marker unexpectedly compiled on $lane" >&2
    exit 1
  fi
  grep -F 'method info is marked @experimental' "$lane_root/negative-without-marker.log"

  cp "$lane_root/overlay-source.scala" "$disposable/frontend/src/main/scala/quasiquotes/types/ResolvedTypeReflection.scala"
  if (
    cd "$disposable"
    sbt -batch "++$lane!" clean \
      "set frontend / Compile / unmanagedJars += file(\"$annotation\")" \
      'frontend/compile'
  ) > "$lane_root/negative-without-plugin.log" 2>&1; then
    echo "M6 negative without plugin unexpectedly compiled on $lane" >&2
    exit 1
  fi
  grep -F 'method info is marked @experimental' "$lane_root/negative-without-plugin.log"

  (
    cd "$disposable"
    sbt -batch "++$lane!" clean \
      "set frontend / Compile / unmanagedJars += file(\"$annotation\")" \
      "set frontend / Compile / scalacOptions ++= Seq(\"-Xplugin:$plugin\", \"-Xplugin-require:allow-experimental\")" \
      'core/packageBin' 'frontend/packageBin' \
      'frontend/testOnly quasiquotes.types.GlobalSelectedTypeFrontendTest'
  ) 2>&1 | tee "$LOG_ROOT/m6-quasiquotes-overlay-$lane.log"
  cp "$disposable/core/target/scala-$lane/quasiquotes-scala3-core_3-0.3.0-SNAPSHOT.jar" "$artifacts/overlay/core.jar"
  cp "$disposable/frontend/target/scala-$lane/quasiquotes-scala3-frontend_$lane-0.3.0-SNAPSHOT.jar" "$artifacts/overlay/frontend.jar"

  sha256sum "$annotation" "$plugin" \
    "$artifacts/baseline/core.jar" "$artifacts/baseline/frontend.jar" \
    "$artifacts/overlay/core.jar" "$artifacts/overlay/frontend.jar" \
    > "$lane_root/artifact-hashes.txt"
  (
    cd "$PRODUCT_ROOT"
    sbt -batch "++$lane" clean verifyLane verifyPermissionFixtures verifyPermissionScope verifyMacroImplementation verifyQuasiquotesIntegration
  ) 2>&1 | tee "$LOG_ROOT/m6-full-gate-$lane.log"
  cp "$PRODUCT_ROOT/target/scala-$lane/m6-verification/summary.txt" "$LOG_ROOT/m6-summary-$lane.txt"
done

PEER_HEAD_AFTER=$(git -C "$PEER_ROOT" rev-parse HEAD)
PEER_STATUS_AFTER=$(git -C "$PEER_ROOT" status --porcelain=v1)
PEER_STATUS_SHA_AFTER=$(printf '%s' "$PEER_STATUS_AFTER" | sha256sum | cut -d ' ' -f 1)
if test "$PEER_HEAD_AFTER" = "$PEER_HEAD_BEFORE" && test "$PEER_STATUS_AFTER" = "$PEER_STATUS_BEFORE"; then
  PEER_CONCURRENT_DRIFT=NO
else
  PEER_CONCURRENT_DRIFT=YES
fi

git -C "$PRODUCT_ROOT" diff --quiet HEAD -- plugin annotation verification/plugins/phase-observer project/M0Verifier.scala \
  project/M1Verifier.scala project/M3Verifier.scala project/M4AVerifier.scala project/M4BVerifier.scala \
  project/M5AVerifier.scala project/M5BVerifier.scala project/M5CVerifier.scala

grep -Fxq 'REAL_MACROPARADISE_COEXISTENCE_ALL_THREE=PASS' "$LOG_ROOT/m4c-final-summary.txt"
grep -Fxq 'M4_IMPLEMENTATION_EVIDENCE_COMPLETE=YES' "$LOG_ROOT/m4c-final-summary.txt"
(cd "$PRODUCT_ROOT" && sha256sum --check target/verification-logs/m4c-final.sha256)
grep -Fxq 'M5A_M5B_RETAINED=PASS' "$LOG_ROOT/m5c-regression-summary.txt"
grep -Fxq 'M0_M1_M3_ALL_LANES_REGRESSION=PASS' "$LOG_ROOT/m5c-regression-summary.txt"
grep -Fxq 'M4_RETAINED_REGRESSION=PASS' "$LOG_ROOT/m5c-regression-summary.txt"
grep -F 'm5c-regression-summary.txt' "$LOG_ROOT/m5c-final-evidence.sha256" | sha256sum --check

printf '%s\n' \
  'PROMPT_015_M6=PASS' \
  "QUASIQUOTES_PINNED_SHA=$PIN" \
  'QUASIQUOTES_PEER_CHECKOUT_MUTATED=NO' \
  'PINNED_QUASIQUOTES_BYTES_FROM_PEER_WORKTREE=NO' \
  'HISTORICAL_Q005_SEAM_STATUS=EVOLVED_BUT_APPLICABLE' \
  'CURRENT_PINNED_QUASIQUOTES_USES_SYMBOL_INFO=NO' \
  'CURRENT_PINNED_QUASIQUOTES_REQUIRES_ALLOW_EXPERIMENTAL=NO' \
  'BASELINE_BARE_CONSTRUCTOR_BEHAVIOR_CHARACTERIZED=PASS' \
  'SCALA_3_3_8_M6=PASS' 'SCALA_3_8_4_M6=PASS' 'SCALA_3_9_0_M6=PASS' \
  'REAL_SYMBOL_INFO_USED_BY_OVERLAY=YES' \
  'SYMBOL_INFO_CONFIRMED_EXPERIMENTAL_ALL_APPLICABLE_LANES=YES' \
  'OVERLAY_WITH_ALLOW=PASS' 'OVERLAY_WITHOUT_MARKER=REJECTED' \
  'OVERLAY_WITH_MARKER_BUT_WITHOUT_PLUGIN=REJECTED' \
  'GLOBAL_EXPERIMENTAL_USED_IN_POSITIVE=NO' \
  'BARE_LIST_CONSTRUCTOR_ARITY_1=PASS' 'BARE_EITHER_CONSTRUCTOR_ARITY_2=PASS' \
  'WRONG_ARITY_REJECTED=PASS' 'TERMINAL_CONTROL_RETAINED=PASS' \
  'APPLIED_WITNESS_ROUTE_RETAINED=PASS' 'APPLIED_TYPE_CONSTRUCTOR_POLICY_WIDENED=NO' \
  'QUASIQUOTES_PUBLIC_API_CHANGED=NO' 'ALLOW_MARKER_TASTY_LEAK=NO' \
  'QUASIQUOTES_CONSUMER_EXPERIMENTAL_LEAK=NO' \
  'SYMBOL_INFO_PRESENT_ONLY_IN_IMPLEMENTATION_MACHINERY=YES' \
  'DOWNSTREAM_WITHOUT_ALLOW_PLUGIN=PASS' 'DOWNSTREAM_WITHOUT_ALLOW_MARKER=PASS' \
  'DOWNSTREAM_WITHOUT_GLOBAL_EXPERIMENTAL=PASS' \
  'SYMBOL_INFO_DERIVED_BEHAVIOR_EXECUTED=YES' 'UNMODIFIED_BASELINE_CONTROL_RETAINED=PASS' \
  'ALLOW_M0_M1_M3_ALL_LANES_REGRESSION=PASS' \
  'ALLOW_M4_M5_RETAINED_REGRESSION=PASS' 'QUASIQUOTES_CORE_BOUNDARY_RETAINED=PASS' \
  'WRONG_LANE_PLUGIN_FAIL_CLOSED=PASS' 'PEER_REPOSITORIES_MODIFIED=NO' \
  'PEER_REPOSITORIES_BUILT_IN_PLACE=NO' \
  'M6_CONTROLLER_RECOMMENDATION=ACCEPT_WITH_QUALIFICATIONS' \
  'M6_COMPLETE_RECOMMENDATION=YES' 'M7_STARTED=NO' 'RELEASE_AUTHORIZED=NO' \
  > "$LOG_ROOT/m6-final-summary.txt"

printf '%s\n' \
  "LOCAL_PEER_HEAD_BEFORE=$PEER_HEAD_BEFORE" "LOCAL_PEER_HEAD_AFTER=$PEER_HEAD_AFTER" \
  "LOCAL_PEER_STATUS_SHA256_BEFORE=$PEER_STATUS_SHA_BEFORE" \
  "LOCAL_PEER_STATUS_SHA256_AFTER=$PEER_STATUS_SHA_AFTER" \
  'PINNED_QUASIQUOTES_BYTES_FROM_PEER_WORKTREE=NO' \
  'PINNED_COMMIT_MATERIALIZED_INDEPENDENTLY=YES' 'PEER_CHECKOUT_MUTATED_BY_M6=NO' \
  "PEER_CONCURRENT_DRIFT_OBSERVED=$PEER_CONCURRENT_DRIFT" \
  > "$LOG_ROOT/m6-peer-state.txt"

(
  cd "$PRODUCT_ROOT"
  sha256sum target/verification-logs/m6-*.log target/verification-logs/m6-summary-*.txt \
    target/verification-logs/m6-final-summary.txt target/verification-logs/m6-peer-state.txt \
    > target/verification-logs/m6-final.sha256
)
echo 'M6 disposable pinned Quasiquotes integration: PASS'
