#!/usr/bin/env bash
set -euo pipefail

PRODUCT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
MAIN="$PRODUCT_ROOT/scripts/verify-macroparadise-coexistence.sh"

bash -n "$MAIN"

required_tokens=(
  'PIN=d773332c29efce90b3af343d34ae5450a93f6d93'
  'M4B_DISPOSABLE="$PRODUCT_ROOT/target/m4b-verification/macroparadise-disposable"'
  'git clone --no-hardlinks --no-checkout "$PEER_ROOT" "$disposable"'
  'test ! -e "$disposable/.git/objects/info/alternates"'
  'm4c_check_peer_toolchain "$disposable"'
  'materialize_peer_lane 3.9.0 "$M4B_DISPOSABLE"'
  'cd "$M4B_DISPOSABLE"'
  'sbt -Dmacroparadise.exactScalaVersion=3.9.0 -batch \'
  "'++3.9.0!' clean 'pluginApi/packageBin' 'plugin/packageBin'"
  'verifyMacroParadiseCoexistence39'
)

for token in "${required_tokens[@]}"; do
  grep -Fq "$token" "$MAIN" || {
    printf 'MACROPARADISE CLEAN ROOM FAIL: missing %s\n' "$token" >&2
    exit 1
  }
done

materialize_line=$(awk '/materialize_peer_lane 3\.9\.0 "\$M4B_DISPOSABLE"/ { print NR; exit }' "$MAIN")
build_line=$(awk '/sbt -Dmacroparadise\.exactScalaVersion=3\.9\.0 -batch/ { print NR; exit }' "$MAIN")
verify_line=$(awk '/verifyMacroParadiseCoexistence39/ { print NR; exit }' "$MAIN")
test -n "$materialize_line" -a -n "$build_line" -a -n "$verify_line"
test "$materialize_line" -lt "$build_line" -a "$build_line" -lt "$verify_line"

if rg -n 'publishLocal|https?://|git@' "$MAIN"; then
  echo 'MACROPARADISE CLEAN ROOM FAIL: publication or remote peer acquisition introduced' >&2
  exit 1
fi

printf '%s\n' 'Macro-Paradise clean-room prerequisite contract: PASS'
