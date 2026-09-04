#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo "Usage: bash scripts/verify-m5b-zinc.sh" >&2
  exit 2
fi

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)

bash "$root/scripts/verify-m5b-zinc-lane.sh" 3.3.8 3.8.4
bash "$root/scripts/verify-m5b-zinc-lane.sh" 3.8.4 3.9.0,3.3.8
