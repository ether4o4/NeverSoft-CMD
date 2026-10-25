#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

WORK_DIR="${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}"
APP_DIR="$WORK_DIR/termux-app"
SRC="$ROOT_DIR/artifacts/bootstrap/bootstrap-aarch64.zip"
DST="$APP_DIR/neversoft-app/src/main/res/raw/neversoft_bootstrap.zip"

[[ -d "$APP_DIR/neversoft-app" ]] || { echo "ERROR: NeverSoft app source not prepared. Run scripts/prepare-app.sh" >&2; exit 1; }
[[ -f "$SRC" ]] || { echo "ERROR: bootstrap not built: $SRC" >&2; exit 1; }

mkdir -p "$(dirname "$DST")"
cp -f "$SRC" "$DST"

printf 'Staged bootstrap: %s -> %s\n' "$SRC" "$DST"
sha256sum "$DST"
