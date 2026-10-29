#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

WORK_DIR="${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}"
PKG_DIR="$WORK_DIR/termux-packages"
OUTPUT_DIR="$PKG_DIR/output"
REPORT_DIR="$ROOT_DIR/artifacts/reports"
REPORT="$REPORT_DIR/built-package-prefix-validation.json"

mkdir -p "$REPORT_DIR"

if [[ ! -d "$OUTPUT_DIR" ]]; then
  echo "No package output directory found: $OUTPUT_DIR" >&2
  exit 2
fi

mapfile -d '' packages < <(find "$OUTPUT_DIR" -maxdepth 1 -type f -name '*.deb' -print0)
(( ${#packages[@]} > 0 )) || { echo "No packages found; validation cannot pass" >&2; exit 2; }
python3 "$ROOT_DIR/scripts/validate-distribution.py" deb "${packages[@]}" --report "$REPORT"
echo "Report: $REPORT"
