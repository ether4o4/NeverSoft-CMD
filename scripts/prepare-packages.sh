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
APT_BUILD="$PKG_DIR/packages/apt/build.sh"

python3 - "$PROPERTIES" "$APT_BUILD" "$NEVERSOFT_PROJECT_NAME" "$NEVERSOFT_APP_ID" "$NEVERSOFT_APT_REPO_URL" "$NEVERSOFT_APT_SUITE" "$NEVERSOFT_APT_COMPONENT" <<'PY'
from pathlib import Path
import re
import sys

properties = Path(sys.argv[1])
apt_build = Path(sys.argv[2])
project_name = sys.argv[3]
app_id = sys.argv[4]
repo_url = sys.argv[5]
suite = sys.argv[6]
component = sys.argv[7]

text = properties.read_text()
for key, value in {
    "TERMUX__NAME": project_name,
    "TERMUX_APP__PACKAGE_NAME": app_id,
}.items():
    pattern = re.compile(rf'^{re.escape(key)}="[^"]*"\s*$', re.MULTILINE)
    text, count = pattern.subn(f'{key}="{value}"', text, count=1)
    if count != 1:
        raise SystemExit(f"Expected exactly one assignment for {key}, found {count}")
properties.write_text(text)

# Never let the forked apt package ship stock Termux repositories. The first
# personal repository is intentionally trusted=yes until NeverSoft's own
# signing key/keyring is introduced in Phase 2.
text = apt_build.read_text()
pattern = re.compile(r'termux_step_post_make_install\(\) \{\s*\{\s*echo "# The main termux repository, with cloudflare cache".*?\}\s*> \$TERMUX_PREFIX/etc/apt/sources\.list', re.S)
replacement = f'''termux_step_post_make_install() {{
\t{{
\t\techo "# NeverSoft native package repository"
\t\techo "deb [trusted=yes] {repo_url} {suite} {component}"
\t}} > $TERMUX_PREFIX/etc/apt/sources.list'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit("Could not replace stock Termux apt repository block")
apt_build.write_text(text)
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

if grep -Fq 'packages-cf.termux.dev' "$APT_BUILD" || grep -Fq 'packages.termux.dev' "$APT_BUILD"; then
  echo "ERROR: stock Termux apt repository survived in packages/apt/build.sh" >&2
  exit 1
fi

if ! grep -Fq "$NEVERSOFT_APT_REPO_URL" "$APT_BUILD"; then
  echo "ERROR: NeverSoft apt repository was not injected" >&2
  exit 1
fi

echo "Prepared custom termux-packages tree at: $PKG_DIR"
