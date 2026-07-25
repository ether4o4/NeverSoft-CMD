#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

SCOPE="${1:-main}"
ARCH="${2:-aarch64}"
MAX_PACKAGES="${NEVERSOFT_MAX_PACKAGES:-0}"
WORK_DIR="${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}"
PKG_DIR="$WORK_DIR/termux-packages"
STATE_DIR="$ROOT_DIR/artifacts/build-state/$ARCH/$SCOPE"
LOG_DIR="$STATE_DIR/logs"
SUCCESS_FILE="$STATE_DIR/success.txt"
FAILED_FILE="$STATE_DIR/failed.txt"

"$ROOT_DIR/scripts/prepare-packages.sh"
mkdir -p "$LOG_DIR"
touch "$SUCCESS_FILE" "$FAILED_FILE"

case "$SCOPE" in
  main) roots=("$PKG_DIR/packages") ;;
  root) roots=("$PKG_DIR/root-packages") ;;
  x11) roots=("$PKG_DIR/x11-packages") ;;
  all) roots=("$PKG_DIR/packages" "$PKG_DIR/root-packages" "$PKG_DIR/x11-packages") ;;
  *) echo "Usage: $0 [main|root|x11|all] [aarch64|arm|i686|x86_64]" >&2; exit 2 ;;
esac

mapfile -t packages < <(
  for root in "${roots[@]}"; do
    [[ -d "$root" ]] || continue
    find "$root" -mindepth 2 -maxdepth 2 -type f -name build.sh -printf '%h\n'
  done | xargs -r -n1 basename | sort -u
)

if (( ${#packages[@]} == 0 )); then
  echo "ERROR: no packages found for scope '$SCOPE'" >&2
  exit 1
fi

built_this_run=0
for pkg in "${packages[@]}"; do
  if grep -Fxq "$pkg" "$SUCCESS_FILE"; then
    continue
  fi

  if (( MAX_PACKAGES > 0 && built_this_run >= MAX_PACKAGES )); then
    echo "Reached NEVERSOFT_MAX_PACKAGES=$MAX_PACKAGES; stopping cleanly."
    break
  fi

  echo
  echo "================================================================"
  echo "Building [$ARCH][$SCOPE] $pkg"
  echo "================================================================"

  log="$LOG_DIR/${pkg}.log"
  set +e
  (
    cd "$PKG_DIR"
    ./scripts/run-docker.sh ./build-package.sh -a "$ARCH" "$pkg"
  ) 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  set -e

  built_this_run=$((built_this_run + 1))
  if (( rc == 0 )); then
    grep -Fvx "$pkg" "$FAILED_FILE" > "$FAILED_FILE.tmp" || true
    mv "$FAILED_FILE.tmp" "$FAILED_FILE"
    grep -Fxq "$pkg" "$SUCCESS_FILE" || echo "$pkg" >> "$SUCCESS_FILE"
    echo "PASS $pkg"
  else
    grep -Fxq "$pkg" "$FAILED_FILE" || echo "$pkg" >> "$FAILED_FILE"
    echo "FAIL $pkg (exit $rc) -- continuing"
  fi
done

sort -u -o "$SUCCESS_FILE" "$SUCCESS_FILE"
sort -u -o "$FAILED_FILE" "$FAILED_FILE"

success_count=$(grep -cve '^$' "$SUCCESS_FILE" || true)
failed_count=$(grep -cve '^$' "$FAILED_FILE" || true)

echo
echo "NeverSoft repository build pass complete."
echo "Successful packages: $success_count"
echo "Current failure queue: $failed_count"
echo "State: $STATE_DIR"
echo "Built debs: $PKG_DIR/output"
