#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

WORK_DIR="${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}"
PKG_DIR="$WORK_DIR/termux-packages"
OUTPUT_DIR="$PKG_DIR/output"
REPORT_DIR="$ROOT_DIR/artifacts/reports"
REPORT="$REPORT_DIR/built-package-prefix-validation.txt"

mkdir -p "$REPORT_DIR"
: > "$REPORT"

if [[ ! -d "$OUTPUT_DIR" ]]; then
  echo "No package output directory found: $OUTPUT_DIR" >&2
  exit 2
fi

count=0
bad=0
while IFS= read -r -d '' deb; do
  count=$((count + 1))
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  (
    cd "$tmp"
    ar x "$deb"
    data=""
    for candidate in data.tar.xz data.tar.gz data.tar.zst data.tar.bz2; do
      [[ -f "$candidate" ]] && data="$candidate" && break
    done
    if [[ -z "$data" ]]; then
      echo "FAIL|$deb|no data archive" >> "$REPORT"
      exit 3
    fi
    mkdir root
    tar -xf "$data" -C root

    if grep -RIl --binary-files=text '/data/data/com\.termux' root >/tmp/neversoft-old-prefix-files 2>/dev/null; then
      echo "FAIL|$deb|contains /data/data/com.termux" >> "$REPORT"
      sed 's#^#  #' /tmp/neversoft-old-prefix-files >> "$REPORT"
      exit 4
    fi

    if ! grep -RIl --binary-files=text "${NEVERSOFT_PREFIX//\//\/}" root >/dev/null 2>&1; then
      # Not every architecture-independent package needs to embed PREFIX. This is informational only.
      echo "INFO|$deb|no literal NeverSoft prefix found" >> "$REPORT"
    else
      echo "PASS|$deb|NeverSoft prefix present; old prefix absent" >> "$REPORT"
    fi
  ) || bad=$((bad + 1))
  rm -rf "$tmp"
done < <(find "$OUTPUT_DIR" -maxdepth 1 -type f -name '*.deb' -print0)

echo "Validated $count package files; hard failures: $bad"
echo "Report: $REPORT"
[[ $bad -eq 0 ]]
