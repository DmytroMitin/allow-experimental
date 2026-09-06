#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
README="$ROOT/README.md"

required_text=(
  '# Allow Experimental'
  '## What it does'
  '## Quick example'
  '## Installation'
  '### Local checkout and publishLocal'
  '### After the first Maven Central release'
  '## Supported exact Scala versions'
  '## Supported permission scope'
  '## Macro implementation use case'
  '## Compiler-plugin coexistence'
  '## Build and lifecycle behavior'
  '## Known limitations'
  '## Development and verification'
  '### Local release preparation rehearsal'
  '## Related projects'
  '## License'
  "sbt -batch '++3.3.8!' 'annotation/publishLocal'"
  "sbt -batch '++3.3.8!' 'plugin/publishLocal'"
  "sbt -batch '++3.8.4!' 'plugin/publishLocal'"
  "sbt -batch '++3.9.0!' 'plugin/publishLocal'"
  'val allowExperimentalVersion = "0.1.0"'
  '"com.github.dmytromitin" %% "allow-experimental-annotation"'
  '"com.github.dmytromitin" % "allow-experimental-plugin"'
  '.cross(CrossVersion.full)'
  '% Provided'
  'No sbt plugin is required or supplied.'
  '[Apache License 2.0](LICENSE)'
  'bash scripts/rehearse-local-release.sh <exact-source-commit>'
  'EPHEMERAL_TEST_ONLY_NOT_FOR_UPLOAD'
)

for expected in "${required_text[@]}"; do
  grep -Fq "$expected" "$README" || {
    printf 'README INSTALLATION FAIL: missing %s\n' "$expected" >&2
    exit 1
  }
done

if rg -n -i '\bM(0|1|3|4A|4B|4C|5A|5B|5C|6|7A)\b|Prompt[ _-]*0*[0-9]+|controller review|publication remains unauthorized' "$README"; then
  echo 'README INSTALLATION FAIL: controller chronology remains' >&2
  exit 1
fi

if rg -n -i 'is (now )?available on Maven Central|artifacts are available from Maven Central' "$README"; then
  echo 'README INSTALLATION FAIL: README claims an unpublished Central artifact exists' >&2
  exit 1
fi

while IFS= read -r target; do
  case "$target" in
    ''|'#'*|http://*|https://*|mailto:*) continue ;;
  esac
  target=${target%%#*}
  if [[ ! -e "$ROOT/$target" ]]; then
    printf 'README INSTALLATION FAIL: missing relative link target %s\n' "$target" >&2
    exit 1
  fi
done < <(grep -oE '\]\([^)]+\)' "$README" | sed -e 's/^](//' -e 's/)$//')

printf '%s\n' 'README installation contract: PASS'
