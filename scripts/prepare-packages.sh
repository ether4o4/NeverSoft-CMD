#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

WORK_DIR="${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}"
PKG_DIR="$WORK_DIR/termux-packages"

mkdir -p "$WORK_DIR"

if [[ ! -d "$PKG_DIR/.git" ]]; then
  rm -rf "$PKG_DIR"
  git clone --depth 1 --branch "$NEVERSOFT_TERMUX_PACKAGES_REF" \
    "$NEVERSOFT_TERMUX_PACKAGES_REPO" "$PKG_DIR"
else
  git -C "$PKG_DIR" fetch --depth 1 origin "$NEVERSOFT_TERMUX_PACKAGES_REF"
  git -C "$PKG_DIR" reset --hard FETCH_HEAD
  git -C "$PKG_DIR" clean -fdx
fi

PROPERTIES="$PKG_DIR/scripts/properties.sh"
python3 - "$PROPERTIES" "$NEVERSOFT_PROJECT_NAME" "$NEVERSOFT_APP_ID" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
project_name = sys.argv[2]
app_id = sys.argv[3]
text = path.read_text()

replacements = {
    "TERMUX__NAME": project_name,
    "TERMUX_APP__PACKAGE_NAME": app_id,
}

for key, value in replacements.items():
    pattern = re.compile(rf'^{re.escape(key)}="[^"]*"\s*$', re.MULTILINE)
    text, count = pattern.subn(f'{key}="{value}"', text, count=1)
    if count != 1:
        raise SystemExit(f"Expected exactly one assignment for {key}, found {count}")

path.write_text(text)
PY

# Confirm the values the Termux build system derives from our fork identity.
bash -lc "source '$PROPERTIES'; printf '%s\n' \
  \"TERMUX__NAME=\$TERMUX__NAME\" \
  \"TERMUX_APP__PACKAGE_NAME=\$TERMUX_APP__PACKAGE_NAME\" \
  \"TERMUX_APP__DATA_DIR=\$TERMUX_APP__DATA_DIR\" \
  \"TERMUX__ROOTFS=\$TERMUX__ROOTFS\" \
  \"TERMUX__HOME=\$TERMUX__HOME\" \
  \"TERMUX__PREFIX=\$TERMUX__PREFIX\""

# Hard fail if the derived prefix is not exactly the NeverSoft prefix.
DERIVED_PREFIX="$(bash -lc "source '$PROPERTIES'; printf '%s' \"\$TERMUX__PREFIX\"")"
if [[ "$DERIVED_PREFIX" != "$NEVERSOFT_PREFIX" ]]; then
  echo "ERROR: derived prefix '$DERIVED_PREFIX' != expected '$NEVERSOFT_PREFIX'" >&2
  exit 1
fi

echo "Prepared custom termux-packages tree at: $PKG_DIR"
