#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

APP_DIR="${1:-${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}/termux-app}"

[[ -d "$APP_DIR" ]] || { echo "ERROR: app source not found: $APP_DIR" >&2; exit 1; }

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

require_text "app/build.gradle" "applicationId \"$NEVERSOFT_APP_ID\""
require_text "app/build.gradle" "manifestPlaceholders.TERMUX_PACKAGE_NAME = \"$NEVERSOFT_APP_ID\""
require_text "app/build.gradle" "manifestPlaceholders.TERMUX_APP_NAME = \"$NEVERSOFT_APP_NAME\""
require_text "app/build.gradle" "include 'arm64-v8a'"
require_text "app/src/main/res/values/strings.xml" "<!ENTITY TERMUX_PACKAGE_NAME \"$NEVERSOFT_APP_ID\">"
require_text "app/src/main/res/values/strings.xml" "<!ENTITY TERMUX_APP_NAME \"$NEVERSOFT_APP_NAME\">"
require_text "termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java" "TERMUX_PACKAGE_NAME = \"$NEVERSOFT_APP_ID\""

forbid_text "app/build.gradle" 'applicationId "com.termux"'
forbid_text "app/src/main/res/xml/shortcuts.xml" 'android:targetPackage="com.termux"'
forbid_text "termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java" 'TERMUX_PACKAGE_NAME = "com.termux"'

# Java namespaces remain com.termux in Phase 1 intentionally. applicationId and
# runtime paths are what must be independent. Report remaining literals so each
# one can be classified before public distribution.
echo
echo "Remaining literal com.termux references (audit only):"
grep -RIn --exclude-dir=.git --exclude='*.md' --exclude='*.txt' 'com\.termux' "$APP_DIR/app" "$APP_DIR/termux-shared" | head -n 120 || true

echo
echo "Remaining literal /data/data/com.termux references:"
if grep -RIn --exclude-dir=.git '/data/data/com\.termux' "$APP_DIR/app" "$APP_DIR/termux-shared"; then
  echo "WARNING: classify the paths above before release. Runtime constants must not point at stock Termux." >&2
fi

if (( fail != 0 )); then
  exit 1
fi

echo "NeverSoft app identity validation passed."
