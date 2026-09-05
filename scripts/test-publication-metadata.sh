#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
BUILD="$ROOT/build.sbt"
LICENSE_FILE="$ROOT/LICENSE"

require_line() {
  local expected=$1
  grep -Fqx "$expected" "$BUILD" || {
    printf 'PUBLICATION METADATA FAIL: missing %s\n' "$expected" >&2
    exit 1
  }
}

require_line 'ThisBuild / organization := "com.github.dmytromitin"'
require_line 'ThisBuild / version := "0.1.0-SNAPSHOT"'
require_line 'ThisBuild / versionScheme := Some("early-semver")'
require_line 'ThisBuild / publishMavenStyle := true'
require_line 'ThisBuild / homepage := Some(url("https://github.com/DmytroMitin/allow-experimental"))'
require_line 'ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))'
require_line 'ThisBuild / pomIncludeRepository := (_ => false)'
require_line '    crossVersion := CrossVersion.binary,'
require_line '    crossVersion := CrossVersion.full,'
require_line '    publish / skip := true,'

if rg -n '^\s*(ThisBuild / )?publishTo\s*:=' "$BUILD"; then
  echo 'PUBLICATION METADATA FAIL: remote publication target is configured' >&2
  exit 1
fi

test -f "$LICENSE_FILE" || {
  echo 'PUBLICATION METADATA FAIL: root LICENSE is missing' >&2
  exit 1
}

actual_license_hash=$(sha256sum "$LICENSE_FILE" | cut -d ' ' -f 1)
expected_license_hash=c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4
if [[ "$actual_license_hash" != "$expected_license_hash" ]]; then
  printf 'PUBLICATION METADATA FAIL: LICENSE hash %s is not the standard Apache-2.0 text\n' \
    "$actual_license_hash" >&2
  exit 1
fi

for task in packageBin packageSrc packageDoc; do
  grep -Fq "Compile / $task / mappings +=" "$BUILD" || {
    printf 'PUBLICATION METADATA FAIL: LICENSE mapping missing from %s\n' "$task" >&2
    exit 1
  }
done

printf '%s\n' 'Publication metadata contract: PASS'
