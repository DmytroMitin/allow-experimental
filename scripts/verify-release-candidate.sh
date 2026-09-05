#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo 'Usage: bash scripts/verify-release-candidate.sh' >&2
  exit 2
fi

SOURCE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
OUTER="$SOURCE_ROOT/target/m7a-clean-checkout"
PRODUCT="$OUTER/allow-experimental"
MACRO_PEER="$OUTER/macroparadise-scala3"
QUASI_PEER="$OUTER/quasiquotes-scala3"
STATE="$OUTER/tool-state"
LOG="$OUTER/release-candidate.log"
MACRO_PIN=d773332c29efce90b3af343d34ae5450a93f6d93
QUASI_PIN=b7425e2f97a42107e78c96454d14f66581889f80

rm -rf -- "$OUTER"
mkdir -p -- "$OUTER" "$STATE"

git clone --no-hardlinks --no-checkout "$SOURCE_ROOT" "$PRODUCT"
git -C "$PRODUCT" checkout --detach "$(git -C "$SOURCE_ROOT" rev-parse HEAD)"
while IFS= read -r -d '' path; do
  mkdir -p -- "$PRODUCT/$(dirname -- "$path")"
  cp -a -- "$SOURCE_ROOT/$path" "$PRODUCT/$path"
done < <(git -C "$SOURCE_ROOT" ls-files --cached --others --exclude-standard -z)

test ! -e "$PRODUCT/target"
test ! -e "$PRODUCT/annotation/target"
test ! -e "$PRODUCT/plugin/target"

git clone --no-hardlinks --no-checkout \
  https://github.com/DmytroMitin/macroparadise-scala3.git "$MACRO_PEER"
git -C "$MACRO_PEER" checkout --detach "$MACRO_PIN"
git clone --no-hardlinks --no-checkout \
  https://github.com/DmytroMitin/quasiquotes-scala3.git "$QUASI_PEER"
git -C "$QUASI_PEER" checkout --detach "$QUASI_PIN"

test "$(git -C "$MACRO_PEER" rev-parse HEAD)" = "$MACRO_PIN"
test "$(git -C "$QUASI_PEER" rev-parse HEAD)" = "$QUASI_PIN"
test -z "$(git -C "$MACRO_PEER" status --porcelain=v1)"
test -z "$(git -C "$QUASI_PEER" status --porcelain=v1)"

cat > "$STATE/repositories" <<'EOF'
[repositories]
  maven-central
EOF

unset JAVA_OPTS
unset COURSIER_REPOSITORIES
export COURSIER_CACHE="$STATE/coursier-cache"
export SBT_OPTS="-Dsbt.global.base=$STATE/global -Dsbt.boot.directory=$STATE/boot -Dsbt.ivy.home=$STATE/ivy -Dsbt.override.build.repos=true -Dsbt.repository.config=$STATE/repositories"

for lane in 3.3.8 3.8.4 3.9.0; do
  cs fetch --classifier sources "org.scala-lang:scala3-compiler_3:$lane" \
    > "$STATE/scala3-compiler-sources-$lane.txt"
  cs fetch --classifier sources "org.scala-lang:scala3-sbt-bridge:$lane" \
    > "$STATE/scala3-sbt-bridge-sources-$lane.txt"
done

{
  printf 'sourceHead=%s\n' "$(git -C "$SOURCE_ROOT" rev-parse HEAD)"
  printf 'sourceStatusSha256=%s\n' "$(git -C "$SOURCE_ROOT" status --porcelain=v1 | sha256sum | cut -d ' ' -f 1)"
  printf 'candidateTree=%s\n' "$(git -C "$PRODUCT" write-tree)"
  printf 'macroParadisePin=%s\n' "$MACRO_PIN"
  printf 'quasiquotesPin=%s\n' "$QUASI_PIN"
  printf '%s\n' 'initialTargetDirectories=NONE'
} > "$OUTER/inputs.txt"

(
  cd "$PRODUCT"
  bash scripts/verify-m4b-macroparadise.sh
  bash scripts/verify-m4c-macroparadise.sh
  bash scripts/verify-m5a.sh
  bash scripts/verify-m5c.sh
  bash scripts/verify-m6-quasiquotes.sh
  bash scripts/verify-m7a-isolated-publication.sh
) 2>&1 | tee "$LOG" >/dev/null

grep -Fxq 'M4B exact Scala 3.9.0 full gate: PASS' "$LOG"
grep -Fxq 'M4C exact Scala 3.3.8/3.8.4 plus retained 3.9.0 full gate: PASS' "$LOG"
grep -Fxq 'M6 disposable pinned Quasiquotes integration: PASS' "$LOG"
grep -Fxq 'M7A isolated filesystem Maven publication and coordinate consumers: PASS' "$LOG"
grep -Fxq 'ISOLATED_FILESYSTEM_MAVEN_DRY_RUN=PASS' "$PRODUCT/target/m7a-evidence/summary.txt"

test "$(git -C "$MACRO_PEER" rev-parse HEAD)" = "$MACRO_PIN"
test "$(git -C "$QUASI_PEER" rev-parse HEAD)" = "$QUASI_PIN"
test -z "$(git -C "$MACRO_PEER" status --porcelain=v1)"
test -z "$(git -C "$QUASI_PEER" status --porcelain=v1)"

sha256sum \
  "$PRODUCT/target/verification-logs/m4b-final-3.9.0.sha256" \
  "$PRODUCT/target/verification-logs/m4c-final.sha256" \
  "$PRODUCT/target/verification-logs/m5c-final-evidence.sha256" \
  "$PRODUCT/target/verification-logs/m6-final.sha256" \
  "$PRODUCT/target/m7a-evidence/repository.sha256" \
  "$PRODUCT/target/m7a-evidence/consumers.sha256" \
  "$OUTER/inputs.txt" \
  > "$OUTER/release-candidate.sha256"

printf '%s\n' \
  'CLEAN_CHECKOUT_RELEASE_GATE=PASS' \
  'M0_THROUGH_M6_RECONSTRUCTED=PASS' \
  'M7A_ISOLATED_COORDINATE_GATE=PASS' \
  'USER_WIDE_PUBLICATION_REPOSITORY_USED=NO' \
  'PEER_REPOSITORIES_MODIFIED=NO' \
  > "$OUTER/summary.txt"

printf '%s\n' 'Allow Experimental clean-checkout release-candidate gate: PASS'
