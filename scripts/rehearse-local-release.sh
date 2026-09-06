#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
SOURCE_IDENTITY=${1:-}
VERSION=0.1.0
ORGANIZATION=com.github.dmytromitin
SCALA_VERSIONS=(3.3.8 3.8.4 3.9.0)

if [[ ! "$SOURCE_IDENTITY" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'Usage: bash scripts/rehearse-local-release.sh <exact-40-hex-source-identity>' >&2
  exit 2
fi
git -C "$ROOT" cat-file -e "$SOURCE_IDENTITY^{commit}"

OUTPUT_ROOT="$ROOT/target/release-candidate-rehearsal"
RAW_REPOSITORY="$OUTPUT_ROOT/raw-repository"
CANDIDATE_REPOSITORY="$OUTPUT_ROOT/candidate-repository"
TOOL_STATE="$OUTPUT_ROOT/tool-state"
EVIDENCE="$OUTPUT_ROOT/evidence"
MANIFEST_JSON="$EVIDENCE/CANDIDATE_MANIFEST.json"
MANIFEST_MARKDOWN="$EVIDENCE/CANDIDATE_MANIFEST.md"
SIGNING_OUTPUT="$OUTPUT_ROOT/signing-output"
CONSUMER_EVIDENCE="$EVIDENCE/release-consumers"
REPOSITORIES="$TOOL_STATE/repositories"

rm -rf -- "$OUTPUT_ROOT"
mkdir -p -- "$RAW_REPOSITORY" "$CANDIDATE_REPOSITORY" "$TOOL_STATE" "$EVIDENCE"

cat > "$REPOSITORIES" <<'EOF'
[repositories]
  maven-central
EOF

export COURSIER_CACHE="$TOOL_STATE/coursier-cache"
SBT=(sbt -batch -Dsbt.supershell=false -Dsbt.server.autostart=false
  "-Dsbt.global.base=$TOOL_STATE/global"
  "-Dsbt.boot.directory=$TOOL_STATE/boot"
  "-Dsbt.ivy.home=$TOOL_STATE/ivy"
  -Dsbt.override.build.repos=true
  "-Dsbt.repository.config=$REPOSITORIES")
isolated_sbt_opts="${SBT_OPTS:-} -Dsbt.global.base=$TOOL_STATE/global -Dsbt.boot.directory=$TOOL_STATE/boot -Dsbt.ivy.home=$TOOL_STATE/ivy -Dsbt.override.build.repos=true -Dsbt.repository.config=$REPOSITORIES"

run_sbt() {
  SBT_OPTS="$isolated_sbt_opts" "${SBT[@]}" "$@"
}

publish_settings=(
  "set ThisBuild / version := \"$VERSION\""
  "set ThisBuild / publishTo := Some(Resolver.file(\"release-candidate-task-local\", file(\"$RAW_REPOSITORY\"))(Resolver.mavenStylePatterns))"
  'set ThisBuild / credentials := Nil'
)

(
  cd "$ROOT"
  run_sbt \
    '++3.3.8!' \
    "${publish_settings[@]}" \
    'annotation/clean' \
    'annotation/publish'
  for lane in "${SCALA_VERSIONS[@]}"; do
    run_sbt \
      "++$lane!" \
      "${publish_settings[@]}" \
      'plugin/clean' \
      'plugin/publish'
  done
) 2>&1 | tee "$EVIDENCE/publish.log"

group_path=com/github/dmytromitin
modules=(
  allow-experimental-annotation_3
  allow-experimental-plugin_3.3.8
  allow-experimental-plugin_3.8.4
  allow-experimental-plugin_3.9.0
)
for module in "${modules[@]}"; do
  raw_directory="$RAW_REPOSITORY/$group_path/$module/$VERSION"
  candidate_directory="$CANDIDATE_REPOSITORY/$group_path/$module/$VERSION"
  base="$module-$VERSION"
  mkdir -p -- "$candidate_directory"
  for filename in "$base.pom" "$base.jar" "$base-sources.jar" "$base-javadoc.jar"; do
    test -s "$raw_directory/$filename"
    cp -- "$raw_directory/$filename" "$candidate_directory/$filename"
    md5sum "$candidate_directory/$filename" | awk '{print $1}' > "$candidate_directory/$filename.md5"
    sha1sum "$candidate_directory/$filename" | awk '{print $1}' > "$candidate_directory/$filename.sha1"
    sha256sum "$candidate_directory/$filename" | awk '{print $1}' > "$candidate_directory/$filename.sha256"
    sha512sum "$candidate_directory/$filename" | awk '{print $1}' > "$candidate_directory/$filename.sha512"
  done
done

python3 "$ROOT/scripts/check-release-repository.py" \
  "$ROOT" \
  "$CANDIDATE_REPOSITORY" \
  --source-identity "$SOURCE_IDENTITY" \
  --json "$MANIFEST_JSON" \
  --markdown "$MANIFEST_MARKDOWN" \
  2>&1 | tee "$EVIDENCE/repository-check.log"

python3 "$ROOT/scripts/rehearse-release-signing.py" \
  "$CANDIDATE_REPOSITORY" \
  "$MANIFEST_JSON" \
  "$SIGNING_OUTPUT" \
  2>&1 | tee "$EVIDENCE/signing.log"

bash "$ROOT/scripts/verify-release-consumers.sh" \
  "$CANDIDATE_REPOSITORY" \
  "$CONSUMER_EVIDENCE" \
  2>&1 | tee "$EVIDENCE/consumers.log"

find "$CANDIDATE_REPOSITORY" -type f -print | LC_ALL=C sort > "$EVIDENCE/candidate-files.txt"
find "$CANDIDATE_REPOSITORY" -type f \( -name '*.pom' -o -name '*.jar' \) -print0 | \
  LC_ALL=C sort -z | xargs -0 sha256sum > "$EVIDENCE/candidate-primary.sha256"

cat > "$EVIDENCE/SUMMARY.txt" <<EOF
LOCAL_RELEASE_REPOSITORY_REHEARSAL=PASS
STRICT_RELEASE_REPOSITORY_CHECK=PASS
EXPECTED_COORDINATE_COUNT=4
EXPECTED_PRIMARY_FILE_COUNT=16
SNAPSHOT_RESIDUE_IN_CANDIDATE=NO
EPHEMERAL_SIGNING_REHEARSAL=PASS
EPHEMERAL_SIGNATURE_COUNT=16
EPHEMERAL_SIGNATURES_VERIFIED=PASS
PRIMARY_BYTES_UNCHANGED_DURING_SIGNING=YES
EPHEMERAL_SECRET_KEY_RETAINED=NO
CENTRAL_TEST_BUNDLE_CLASSIFICATION=EPHEMERAL_TEST_ONLY_NOT_FOR_UPLOAD
RELEASE_CONSUMER_3_3_8=PASS
RELEASE_CONSUMER_3_8_4=PASS
RELEASE_CONSUMER_3_9_0=PASS
CENTRAL_TOKEN_READ=NO
CENTRAL_TOKEN_USED=NO
REAL_RELEASE_SIGNING_KEY_USED=NO
REMOTE_ARTIFACT_PUBLICATION=NO
SOURCE_IDENTITY=$SOURCE_IDENTITY
EOF

echo 'ALLOW_EXPERIMENTAL_0_1_0_LOCAL_RELEASE_REHEARSAL=PASS'
echo "manifest_json=$MANIFEST_JSON"
echo "test_bundle=$SIGNING_OUTPUT/allow-experimental-central-bundle-EPHEMERAL-TEST-ONLY.zip"
