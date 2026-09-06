#!/usr/bin/env bash
set -euo pipefail

PRODUCT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
HELPER="$PRODUCT_ROOT/scripts/macroparadise-toolchain.sh"
MAIN="$PRODUCT_ROOT/scripts/verify-macroparadise-coexistence.sh"

if test ! -f "$HELPER"; then
  echo "TOOLCHAIN TEST FAIL: reusable M4C peer-build toolchain guard is missing" >&2
  exit 1
fi

# shellcheck source=macroparadise-toolchain.sh
source "$HELPER"

build_started=NO
negative_output=""
if negative_output=$(m4c_require_toolchain_values 24 1.12.15 2>&1); then
  build_started=YES
fi
test "$build_started" = NO
test "$negative_output" = \
  "M4C TOOLCHAIN FAIL: peer build requires JDK feature 25, found 24"
echo "TOOLCHAIN TEST PASS: Java feature 24 rejected before peer build"

build_started=NO
negative_output=""
if negative_output=$(m4c_require_toolchain_values 25 1.12.14 2>&1); then
  build_started=YES
fi
test "$build_started" = NO
test "$negative_output" = \
  "M4C TOOLCHAIN FAIL: pinned peer requires sbt 1.12.15, found 1.12.14"
echo "TOOLCHAIN TEST PASS: sbt 1.12.14 rejected before peer build"

m4c_require_toolchain_values 25 1.12.15
echo "TOOLCHAIN TEST PASS: exact Java 25 and sbt 1.12.15 admitted"

fixture_root=$(mktemp -d "${TMPDIR:-/tmp}/allow-experimental-toolchain-test.XXXXXX")
trap 'rm -rf -- "$fixture_root"' EXIT
for lane in 3.3.8 3.8.4; do
  fixture_peer="$fixture_root/macroparadise-$lane"
  mkdir -p "$fixture_peer/project"
  printf '%s\n' 'sbt.version=1.12.15' > "$fixture_peer/project/build.properties"
  m4c_check_peer_toolchain "$fixture_peer"
done
echo "TOOLCHAIN TEST PASS: task-local peer fixtures validate with the live toolchain"

check_line=$(awk '/m4c_check_peer_toolchain "\$disposable"/ { print NR; exit }' "$MAIN")
build_line=$(awk '/sbt -Dmacroparadise\.exactScalaVersion=/ { print NR; exit }' "$MAIN")
test -n "$check_line" -a -n "$build_line" -a "$check_line" -lt "$build_line"
echo "TOOLCHAIN TEST PASS: reusable full script validates before invoking peer sbt"

bash -n "$HELPER" "$MAIN" "$0"
echo "TOOLCHAIN TEST PASS: helper, full script, and test syntax"
