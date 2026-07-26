#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

WORK_DIR="${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}"
APP_DIR="$WORK_DIR/termux-app"
NEVERSOFT_MODULE_SOURCE="$ROOT_DIR/android/neversoft-app"
NEVERSOFT_MODULE="$APP_DIR/neversoft-app"

mkdir -p "$WORK_DIR"

if [[ ! -d "$APP_DIR/.git" ]]; then
  rm -rf "$APP_DIR"
  git clone --depth 1 --branch "$NEVERSOFT_TERMUX_APP_REF" \
    "$NEVERSOFT_TERMUX_APP_REPO" "$APP_DIR"
else
  git -C "$APP_DIR" fetch --depth 1 origin "$NEVERSOFT_TERMUX_APP_REF"
  git -C "$APP_DIR" reset --hard FETCH_HEAD
  git -C "$APP_DIR" clean -fdx
fi

[[ -d "$NEVERSOFT_MODULE_SOURCE" ]] || {
  echo "ERROR: NeverSoft Android module template missing: $NEVERSOFT_MODULE_SOURCE" >&2
  exit 1
}

# NeverSoft owns the Android application. We only reuse Termux's proven terminal
# emulator/view libraries and PTY JNI layer. The upstream Termux app module and
# termux-shared module are deliberately not part of the Gradle build.
rm -rf "$NEVERSOFT_MODULE"
cp -a "$NEVERSOFT_MODULE_SOURCE" "$NEVERSOFT_MODULE"
cat > "$APP_DIR/settings.gradle" <<'EOF'
include ':terminal-emulator', ':terminal-view', ':neversoft-app'
EOF

# Phase 1 is ARM64-only. Avoid building unused emulator JNI ABIs.
python3 - "$APP_DIR/terminal-emulator/build.gradle" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
old = "abiFilters 'x86', 'x86_64', 'armeabi-v7a', 'arm64-v8a'"
new = "abiFilters 'arm64-v8a'"
if old not in text:
    if new not in text:
        raise SystemExit("Could not restrict terminal-emulator to arm64-v8a")
else:
    text = text.replace(old, new, 1)
path.write_text(text)
PY

"$ROOT_DIR/scripts/validate-app-source.sh" "$APP_DIR"
echo "Prepared NeverSoft-owned Android app source at: $APP_DIR"
