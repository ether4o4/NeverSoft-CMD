#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WAVE="${1:-1}"
ARCH="${2:-aarch64}"
WORK_DIR="${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}"
PKG_DIR="$WORK_DIR/termux-packages"
STATUS_DIR="$ROOT_DIR/artifacts/status"
LOG_DIR="$ROOT_DIR/artifacts/logs/wave-$WAVE-$ARCH"
WAVES_FILE="$ROOT_DIR/config/package-waves.txt"

"$ROOT_DIR/scripts/prepare-packages.sh"
mkdir -p "$STATUS_DIR" "$LOG_DIR"
STATUS_FILE="$STATUS_DIR/wave-$WAVE-$ARCH.tsv"
printf 'package\tarch\tstatus\tlog\n' > "$STATUS_FILE"

mapfile -t PACKAGES < <(awk -F'|' -v wave="$WAVE" '$0 !~ /^#/ && $1 == wave {print $2}' "$WAVES_FILE")
if [[ ${#PACKAGES[@]} -eq 0 ]]; then
  echo "No packages configured for wave $WAVE" >&2
  exit 2
fi

failed=0
for pkg in "${PACKAGES[@]}"; do
  log="$LOG_DIR/$pkg.log"
  echo "=== Building $pkg for $ARCH (wave $WAVE) ==="
  if (cd "$PKG_DIR" && ./scripts/run-docker.sh ./build-package.sh -a "$ARCH" "$pkg") > >(tee "$log") 2>&1; then
    printf '%s\t%s\tPASS\t%s\n' "$pkg" "$ARCH" "$log" >> "$STATUS_FILE"
  else
    rc=$?
    printf '%s\t%s\tFAIL(%s)\t%s\n' "$pkg" "$ARCH" "$rc" "$log" >> "$STATUS_FILE"
    failed=$((failed + 1))
  fi
done

echo
echo "Build status: $STATUS_FILE"
cat "$STATUS_FILE"

if [[ $failed -ne 0 ]]; then
  echo "$failed package(s) failed in wave $WAVE" >&2
  exit 1
fi
