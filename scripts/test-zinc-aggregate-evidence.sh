#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
ENTRY="$ROOT/scripts/verify-zinc-lifecycle.sh"
WORK=$(mktemp -d /tmp/allow-experimental-zinc-aggregate-test.XXXXXX)
trap 'rm -rf "$WORK"' EXIT

PRODUCT="$WORK/product"
FAKE_BIN="$WORK/fake-bin"
STATE="$WORK/state"
LOGS="$PRODUCT/target/verification-logs"
mkdir -p "$PRODUCT/scripts" "$FAKE_BIN" "$STATE" "$LOGS"
cp "$ENTRY" "$PRODUCT/scripts/verify-zinc-lifecycle.sh"

cat > "$STATE/expected-summary.txt" <<'EOF'
SCALA_3_3_8_CORE=PASS
SCALA_3_8_4_CORE=PASS
SCALA_3_9_0_CORE=PASS
M4C_SCALA_3_3_8=PASS
M4C_SCALA_3_8_4=PASS
M4A_SCALA_3_9_0=PASS
M4B_SCALA_3_9_0=PASS
READ_ONLY_PEERS_BUILT_FOR_M5A=NO
EOF
printf '%s\n' 'STALE_EVIDENCE=DO_NOT_REUSE' > "$STATE/stale-summary.txt"
cp "$STATE/stale-summary.txt" "$LOGS/m5a-regression-summary.txt"

cat > "$PRODUCT/scripts/acquire-lifecycle-audit-sources.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' ACQUIRE >> "$AGG_TEST_STATE/events"
EOF

cat > "$PRODUCT/scripts/verify-zinc-lifecycle-3.9.0-fixture.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
summary=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)/target/verification-logs/m5a-regression-summary.txt
cmp "$AGG_TEST_STATE/expected-summary.txt" "$summary" || {
  echo 'ZINC AGGREGATE CONTRACT FAIL: 3.9 fixture did not receive freshly aggregated evidence' >&2
  exit 1
}
test "$(cat "$AGG_TEST_STATE/prerequisite-count")" = 3
printf '%s\n' FIXTURE_39 >> "$AGG_TEST_STATE/events"
EOF

cat > "$PRODUCT/scripts/verify-zinc-lifecycle-older-lanes.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' OLDER_LANES >> "$AGG_TEST_STATE/events"
EOF

cat > "$FAKE_BIN/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  *'rev-parse HEAD') printf '%s\n' d773332c29efce90b3af343d34ae5450a93f6d93 ;;
  *'status --porcelain=v1') : ;;
  *) printf 'ZINC AGGREGATE CONTRACT FAIL: unexpected git command: %s\n' "$*" >&2; exit 1 ;;
esac
EOF

cat > "$FAKE_BIN/sha256sum" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == --check ]]; then
  exit 0
fi
for file in "$@"; do
  printf '0000000000000000000000000000000000000000000000000000000000000000  %s\n' "$file"
done
EOF

cat > "$FAKE_BIN/sbt" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
summary="$AGG_TEST_PRODUCT/target/verification-logs/m5a-regression-summary.txt"
case " $* " in
  *' -experimental '*|*publish*|*release*)
    printf 'ZINC AGGREGATE CONTRACT FAIL: forbidden sbt task or global flag: %s\n' "$*" >&2
    exit 1
    ;;
esac
case "$*" in
  *verifySameJvmLifecycleOlder*|*verifySameJvmLifecycle39*)
    count=0
    if [[ -f "$AGG_TEST_STATE/prerequisite-count" ]]; then
      count=$(cat "$AGG_TEST_STATE/prerequisite-count")
    fi
    printf '%s\n' "$((count + 1))" > "$AGG_TEST_STATE/prerequisite-count"
    printf '%s\n' PREREQUISITE >> "$AGG_TEST_STATE/events"
    if [[ "${AGG_TEST_FAIL_PREREQUISITE:-0}" == 1 ]]; then
      exit 17
    fi
    ;;
  *verifyZincLifecycle39*)
    cmp "$AGG_TEST_STATE/expected-summary.txt" "$summary" || {
      echo 'ZINC AGGREGATE CONTRACT FAIL: strict 3.9 verifier did not receive freshly aggregated evidence' >&2
      exit 1
    }
    test "$(cat "$AGG_TEST_STATE/prerequisite-count")" = 3
    printf '%s\n' FINAL_39 >> "$AGG_TEST_STATE/events"
    ;;
  *verifyZincLifecycle*)
    printf '%s\n' FINAL_ALL >> "$AGG_TEST_STATE/events"
    ;;
  *) printf 'ZINC AGGREGATE CONTRACT FAIL: unexpected sbt command: %s\n' "$*" >&2; exit 1 ;;
esac
EOF

chmod +x "$PRODUCT/scripts/"*.sh "$FAKE_BIN/"*
printf '%s\n' 0 > "$STATE/prerequisite-count"

set +e
PATH="$FAKE_BIN:$PATH" \
AGG_TEST_PRODUCT="$PRODUCT" \
AGG_TEST_STATE="$STATE" \
AGG_TEST_FAIL_PREREQUISITE=1 \
bash "$PRODUCT/scripts/verify-zinc-lifecycle.sh" > "$STATE/prerequisite-failure.log" 2>&1
prerequisite_failure=$?
set -e
test "$prerequisite_failure" = 17
cmp "$STATE/stale-summary.txt" "$LOGS/m5a-regression-summary.txt"
printf '%s\n' ACQUIRE PREREQUISITE > "$STATE/expected-failure-events"
cmp "$STATE/expected-failure-events" "$STATE/events"

rm -f "$STATE/events"
printf '%s\n' 0 > "$STATE/prerequisite-count"
cp "$STATE/stale-summary.txt" "$LOGS/m5a-regression-summary.txt"

PATH="$FAKE_BIN:$PATH" \
AGG_TEST_PRODUCT="$PRODUCT" \
AGG_TEST_STATE="$STATE" \
bash "$PRODUCT/scripts/verify-zinc-lifecycle.sh"

cmp "$STATE/expected-summary.txt" "$LOGS/m5a-regression-summary.txt"
cat > "$STATE/expected-events" <<'EOF'
ACQUIRE
PREREQUISITE
PREREQUISITE
PREREQUISITE
FIXTURE_39
FINAL_39
OLDER_LANES
FINAL_ALL
EOF
cmp "$STATE/expected-events" "$STATE/events"

printf '%s\n' 'Zinc fresh M5A aggregate evidence contract: PASS'
