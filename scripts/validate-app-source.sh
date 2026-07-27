#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

APP_DIR="${1:-${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}/termux-app}"
MODULE="$APP_DIR/neversoft-app"

[[ -d "$APP_DIR" ]] || { echo "ERROR: app source not found: $APP_DIR" >&2; exit 1; }
[[ -d "$MODULE" ]] || { echo "ERROR: NeverSoft app module missing: $MODULE" >&2; exit 1; }

fail=0

require_text() {
  local file="$1" text="$2"
  if ! grep -Fq -- "$text" "$APP_DIR/$file"; then
    echo "ERROR: $file missing expected text: $text" >&2
    fail=1
  fi
}

forbid_text() {
  local file="$1" text="$2"
  if grep -Fq -- "$text" "$APP_DIR/$file"; then
    echo "ERROR: $file still contains forbidden text: $text" >&2
    fail=1
  fi
}

require_text "settings.gradle" "':terminal-emulator'"
require_text "settings.gradle" "':terminal-view'"
require_text "settings.gradle" "':neversoft-app'"
forbid_text "settings.gradle" "':app'"
forbid_text "settings.gradle" "':termux-shared'"

require_text "neversoft-app/build.gradle" "applicationId \"$NEVERSOFT_APP_ID\""
require_text "neversoft-app/build.gradle" "abiFilters 'arm64-v8a'"
require_text "neversoft-app/src/main/AndroidManifest.xml" "package=\"$NEVERSOFT_APP_ID\""
require_text "neversoft-app/src/main/res/layout/activity_main.xml" "@+id/ai_pane"
require_text "neversoft-app/src/main/res/layout/activity_main.xml" "@+id/split_handle"
require_text "neversoft-app/src/main/res/layout/activity_main.xml" "@+id/terminal_view"
require_text "neversoft-app/src/main/java/com/neversoft/shell/MainActivity.java" "new TerminalSession"
require_text "neversoft-app/src/main/java/com/neversoft/shell/BootstrapInstaller.java" "SYMLINKS.txt"
require_text "neversoft-app/src/main/java/com/neversoft/shell/ShellRuntime.java" "STOCK_PREFIX"
require_text "neversoft-app/src/main/java/com/neversoft/shell/ai/HuggingFaceModelManager.java" "huggingface.co"
require_text "terminal-emulator/build.gradle" "abiFilters 'arm64-v8a'"

# Historical /data/data/com.termux is now an intentional *virtual alias* used only
# by the compatibility layer for stock Termux packages with absolute paths. It
# must never become NeverSoft's real data/home/prefix identity anywhere else.
mapfile -t stock_path_hits < <(grep -RIl --exclude-dir=build '/data/data/com\.termux' "$MODULE" || true)
for file in "${stock_path_hits[@]}"; do
  rel="${file#$MODULE/}"
  case "$rel" in
    src/main/java/com/neversoft/shell/ShellRuntime.java|src/main/java/com/neversoft/shell/BootstrapInstaller.java)
      ;;
    *)
      echo "ERROR: unexpected stock Termux runtime path outside compatibility layer: $rel" >&2
      fail=1
      ;;
  esac
done

if grep -RIn --exclude-dir=build 'applicationId[[:space:]]*"com\.termux"' "$MODULE"; then
  echo "ERROR: NeverSoft-owned app module contains stock Termux applicationId" >&2
  fail=1
fi

if (( fail != 0 )); then
  exit 1
fi

echo "NeverSoft-owned app validation passed."
