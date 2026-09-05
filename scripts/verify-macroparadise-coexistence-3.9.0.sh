#!/usr/bin/env bash
set -euo pipefail

PRODUCT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
PEER_ROOT="$PRODUCT_ROOT/../macroparadise-scala3"
PIN=d773332c29efce90b3af343d34ae5450a93f6d93
DISPOSABLE="$PRODUCT_ROOT/target/m4b-verification/macroparadise-disposable"
LOG_ROOT="$PRODUCT_ROOT/target/verification-logs"
PEER_BUILD_LOG="$LOG_ROOT/m4b-macroparadise-build-3.9.0.log"
PRODUCT_GATE_LOG="$LOG_ROOT/m4b-full-gate-3.9.0.log"
PEER_STATE_LOG="$LOG_ROOT/m4b-peer-state-3.9.0.txt"

mkdir -p "$PRODUCT_ROOT/target/m4b-verification" "$LOG_ROOT"

PEER_HEAD_BEFORE=$(git -C "$PEER_ROOT" rev-parse HEAD)
PEER_STATUS_BEFORE=$(git -C "$PEER_ROOT" status --porcelain=v1)
if test -n "$PEER_STATUS_BEFORE"; then
  PEER_DIRTY_BEFORE=YES
else
  PEER_DIRTY_BEFORE=NO
fi
PEER_STATUS_SHA256_BEFORE=$(printf '%s' "$PEER_STATUS_BEFORE" | sha256sum | cut -d ' ' -f 1)
git -C "$PEER_ROOT" cat-file -e "$PIN^{commit}"

if test ! -d "$DISPOSABLE/.git"; then
  git clone --no-hardlinks --no-checkout "$PEER_ROOT" "$DISPOSABLE"
  git -C "$DISPOSABLE" checkout --detach "$PIN"
fi

test "$(git -C "$DISPOSABLE" rev-parse HEAD)" = "$PIN"
test -z "$(git -C "$DISPOSABLE" status --porcelain=v1)"
git -C "$DISPOSABLE" diff --quiet "$PIN" --
git -C "$DISPOSABLE" diff --cached --quiet
test ! -e "$DISPOSABLE/.git/objects/info/alternates"

COMPARABLE_OBJECTS=0
while IFS= read -r OBJECT_PATH; do
  SOURCE_OBJECT="$PEER_ROOT/.git/objects/$OBJECT_PATH"
  COPIED_OBJECT="$DISPOSABLE/.git/objects/$OBJECT_PATH"
  if test -f "$SOURCE_OBJECT"; then
    test "$(stat -c '%d:%i' "$SOURCE_OBJECT")" != "$(stat -c '%d:%i' "$COPIED_OBJECT")"
    COMPARABLE_OBJECTS=$((COMPARABLE_OBJECTS + 1))
  fi
done < <(find "$DISPOSABLE/.git/objects" -type f -printf '%P\n')
test "$COMPARABLE_OBJECTS" -gt 0
printf 'M4B independent Git object comparisons: %s\n' "$COMPARABLE_OBJECTS"

(
  cd "$DISPOSABLE"
  sbt -Dmacroparadise.exactScalaVersion=3.9.0 -batch \
    '++3.9.0!' clean 'pluginApi/packageBin' 'plugin/packageBin'
) 2>&1 | tee "$PEER_BUILD_LOG"

(
  cd "$PRODUCT_ROOT"
  sbt -batch '++3.9.0' clean verifyLane verifyPermissionFixtures verifyPermissionScope verifyMacroImplementation verifyPhaseObserverCoexistence verifyMacroParadiseCoexistence39
) 2>&1 | tee "$PRODUCT_GATE_LOG"

PEER_HEAD_AFTER=$(git -C "$PEER_ROOT" rev-parse HEAD)
PEER_STATUS_AFTER=$(git -C "$PEER_ROOT" status --porcelain=v1)
test "$PEER_HEAD_AFTER" = "$PEER_HEAD_BEFORE"
test "$PEER_STATUS_AFTER" = "$PEER_STATUS_BEFORE"
PEER_STATUS_SHA256_AFTER=$(printf '%s' "$PEER_STATUS_AFTER" | sha256sum | cut -d ' ' -f 1)

printf '%s\n' \
  "LOCAL_PEER_HEAD_BEFORE=$PEER_HEAD_BEFORE" \
  "LOCAL_PEER_HEAD_AFTER=$PEER_HEAD_AFTER" \
  "LOCAL_PEER_WORKTREE_DIRTY=$PEER_DIRTY_BEFORE" \
  "LOCAL_PEER_STATUS_SHA256_BEFORE=$PEER_STATUS_SHA256_BEFORE" \
  "LOCAL_PEER_STATUS_SHA256_AFTER=$PEER_STATUS_SHA256_AFTER" \
  'PINNED_PEER_BYTES_FROM_DIRTY_WORKTREE=NO' \
  'PINNED_COMMIT_MATERIALIZED_INDEPENDENTLY=YES' \
  'PEER_CHECKOUT_MUTATED=NO' \
  > "$PEER_STATE_LOG"

cp "$PRODUCT_ROOT/target/scala-3.9.0/m4b-verification/summary.txt" \
  "$LOG_ROOT/m4b-final-summary-3.9.0.txt"

(
  cd "$PRODUCT_ROOT"
  sha256sum \
    target/verification-logs/m4b-macroparadise-build-3.9.0.log \
    target/verification-logs/m4b-full-gate-3.9.0.log \
    target/verification-logs/m4b-peer-state-3.9.0.txt \
    target/verification-logs/m4b-final-summary-3.9.0.txt \
    target/scala-3.9.0/m4b-verification/peer-build.txt \
    target/scala-3.9.0/m4b-verification/source-hashes.txt \
    target/scala-3.9.0/m4b-verification/phase-plan-allow-first/observer.log \
    target/scala-3.9.0/m4b-verification/phase-plan-macroparadise-first/observer.log \
    > target/verification-logs/m4b-final-3.9.0.sha256
)

printf '%s\n' 'M4B exact Scala 3.9.0 full gate: PASS'
