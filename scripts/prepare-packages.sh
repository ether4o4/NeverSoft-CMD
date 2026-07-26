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
TERMUX_AM_BUILD="$PKG_DIR/packages/termux-am/build.sh"

python3 - "$PROPERTIES" "$APT_BUILD" "$TERMUX_AM_BUILD" "$NEVERSOFT_PROJECT_NAME" "$NEVERSOFT_APP_ID" "$NEVERSOFT_APT_REPO_URL" "$NEVERSOFT_APT_SUITE" "$NEVERSOFT_APT_COMPONENT" <<'PY'
from pathlib import Path
import re
import sys

properties = Path(sys.argv[1])
apt_build = Path(sys.argv[2])
termux_am_build = Path(sys.argv[3])
project_name = sys.argv[4]
app_id = sys.argv[5]
repo_url = sys.argv[6]
suite = sys.argv[7]
component = sys.argv[8]

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

# The package-builder image exposes an Android SDK that is readable but not
# writable by the builder account. TermuxAm uses an older Android Gradle Plugin
# which auto-installs compileSdk 33 and Build Tools 30.0.3. Redirect that one
# package to a writable SDK copy so Gradle can install its missing components.
#
# Do NOT rename TermuxAm's Java namespace. Its source package is
# com.termux.termuxam and BuildConfig is generated in that namespace. Changing
# the Gradle namespace alone breaks compilation. Instead, replace the fallback
# BuildConfig reference in FakeContext with the fork's app package literal.
text = termux_am_build.read_text()
old = '''termux_step_post_get_source() {
\tsed -i'' -E -e "s|\\@TERMUX_PREFIX\\@|${TERMUX_PREFIX}|g" "$TERMUX_PKG_SRCDIR/am-libexec-packaged"
\tsed -i'' -E -e "s|\\@TERMUX_APP_PACKAGE\\@|${TERMUX_APP_PACKAGE}|g" "$TERMUX_PKG_SRCDIR/app/src/main/java/com/termux/termuxam/FakeContext.java"
}'''
new = '''termux_step_post_get_source() {
\tsed -i'' -E -e "s|\\@TERMUX_PREFIX\\@|${TERMUX_PREFIX}|g" "$TERMUX_PKG_SRCDIR/am-libexec-packaged"
\tlocal fake_context="$TERMUX_PKG_SRCDIR/app/src/main/java/com/termux/termuxam/FakeContext.java"
\tsed -i'' -E -e "s|\\@TERMUX_APP_PACKAGE\\@|${TERMUX_APP_PACKAGE}|g" "$fake_context"
\t# The placeholder above is the normal runtime path. Replace the legacy
\t# BuildConfig fallback too so this helper remains fork-safe without moving
\t# its internal Java namespace (which would break BuildConfig resolution).
\tsed -i'' -E -e "s|BuildConfig\\.TERMUX_PACKAGE_NAME|\\\"${TERMUX_APP_PACKAGE}\\\"|g" "$fake_context"
}'''
if old not in text:
    raise SystemExit("Could not locate termux-am post-get-source hook")
text = text.replace(old, new, 1)

needle = '''termux_step_make() {
\t# Download and use a new enough gradle version to avoid the process hanging after running:'''
replacement = '''termux_step_make() {
\t# The package-builder SDK is read-only to the builder user. Gradle for
\t# TermuxAm needs to add Android 33 + Build Tools 30.0.3, so make a writable
\t# per-package SDK copy first. This is build-host plumbing only; it is not
\t# shipped into the NeverSoft package.
\tlocal readonly_android_home="$ANDROID_HOME"
\tlocal writable_android_home="$TERMUX_PKG_TMPDIR/android-sdk"
\trm -rf "$writable_android_home"
\tcp -a "$readonly_android_home" "$writable_android_home"
\tchmod -R u+rwX "$writable_android_home"
\texport ANDROID_HOME="$writable_android_home"
\texport ANDROID_SDK_ROOT="$writable_android_home"

\t# Download and use a new enough gradle version to avoid the process hanging after running:'''
if needle not in text:
    raise SystemExit("Could not locate termux-am make hook")
text = text.replace(needle, replacement, 1)
termux_am_build.write_text(text)
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

if ! grep -Fq 'writable_android_home' "$TERMUX_AM_BUILD"; then
  echo "ERROR: termux-am writable Android SDK patch was not injected" >&2
  exit 1
fi

if grep -Fq 's|com\\.termux|${TERMUX_APP_PACKAGE}|g' "$TERMUX_AM_BUILD"; then
  echo "ERROR: broad termux-am namespace rewrite survived" >&2
  exit 1
fi

# The injected build.sh contains a single regex escape before the dot.
if ! grep -Fq 'BuildConfig\.TERMUX_PACKAGE_NAME' "$TERMUX_AM_BUILD"; then
  echo "ERROR: termux-am BuildConfig fallback patch was not injected" >&2
  exit 1
fi

echo "Prepared custom termux-packages tree at: $PKG_DIR"
