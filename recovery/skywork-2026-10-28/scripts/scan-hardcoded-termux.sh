#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}"
PKG_DIR="$WORK_DIR/termux-packages"
REPORT_DIR="$ROOT_DIR/artifacts/reports"
REPORT="$REPORT_DIR/hardcoded-termux.txt"

if [[ ! -d "$PKG_DIR" ]]; then
  "$ROOT_DIR/scripts/prepare-packages.sh"
fi

mkdir -p "$REPORT_DIR"
{
  echo "NeverSoft hard-coded Termux reference scan"
  echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "=== /data/data/com.termux references ==="
  grep -RIn --exclude-dir=.git --exclude='*.patch' '/data/data/com\.termux' "$PKG_DIR" || true
  echo
  echo "=== literal com.termux references ==="
  grep -RIn --exclude-dir=.git --exclude='*.patch' 'com\.termux' "$PKG_DIR" || true
} > "$REPORT"

printf 'Old-prefix references: '
grep -c '/data/data/com\.termux' "$REPORT" || true
printf 'Package-id references: '
grep -c 'com\.termux' "$REPORT" || true

echo "Full report: $REPORT"
