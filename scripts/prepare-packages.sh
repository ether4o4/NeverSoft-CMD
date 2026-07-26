#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

WORK_DIR="${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}"
PKG_DIR="$WORK_DIR/termux-packages"
REPORT_DIR="$ROOT_DIR/artifacts/reports"

mkdir -p "$WORK_DIR" "$REPORT_DIR"

# Support immutable commit SHAs as well as tags/branches. Using --branch with a
# raw SHA does not work reliably, so always fetch the exact configured ref.
if [[ ! -d "$PKG_DIR/.git" ]]; then
  rm -rf "$PKG_DIR"
  git init "$PKG_DIR"
  git -C "$PKG_DIR" remote add origin "$NEVERSOFT_TERMUX_PACKAGES_REPO"
fi
git -C "$PKG_DIR" fetch --depth 1 origin "$NEVERSOFT_TERMUX_PACKAGES_REF"
git -C "$PKG_DIR" checkout --detach -f FETCH_HEAD
git -C "$PKG_DIR" clean -fdx
RESOLVED_SHA="$(git -C "$PKG_DIR" rev-parse HEAD)"
printf 'termux-packages=%s\n' "$RESOLVED_SHA" > "$REPORT_DIR/upstream-refs.txt"

PROPERTIES="$PKG_DIR/scripts/properties.sh"
APT_BUILD="$PKG_DIR/packages/apt/build.sh"
BOOTSTRAP_BUILD="$PKG_DIR/scripts/build-bootstraps.sh"

python3 - "$PROPERTIES" "$APT_BUILD" "$BOOTSTRAP_BUILD" "$NEVERSOFT_PROJECT_NAME" "$NEVERSOFT_APP_ID" "$NEVERSOFT_APT_REPO_URL" "$NEVERSOFT_APT_SUITE" "$NEVERSOFT_APT_COMPONENT" <<'PY'
from pathlib import Path
import re
import sys

properties = Path(sys.argv[1])
apt_build = Path(sys.argv[2])
bootstrap_build = Path(sys.argv[3])
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

# NeverSoft owns its repository. Stock Termux repositories are not valid for our
# different native prefix and must never be emitted by the bootstrap apt package.
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

# Phase 1 does not need apt's generated manpages. Disabling them removes the
# docbook-xsl build dependency tree (which was pulling font/fontconfig packages
# and made a flaky freedesktop GitLab archive a bootstrap blocker).
old_build_deps = 'TERMUX_PKG_BUILD_DEPENDS="docbook-xsl,libdb"'
if old_build_deps in text:
    text = text.replace(old_build_deps, 'TERMUX_PKG_BUILD_DEPENDS="libdb"', 1)
elif 'TERMUX_PKG_BUILD_DEPENDS="libdb"' not in text:
    raise SystemExit("Could not normalize apt build dependencies")

if '-DWITH_DOC_MANPAGES=ON' in text:
    text = text.replace('-DWITH_DOC_MANPAGES=ON', '-DWITH_DOC_MANPAGES=OFF', 1)
elif '-DWITH_DOC_MANPAGES=OFF' not in text:
    raise SystemExit("Could not disable apt manpage build")
apt_build.write_text(text)

# Build a NeverSoft bootstrap, not a clone of Termux's convenience bundle.
# Keep the native package manager + shell + core Unix tools. Everything else is
# installable later from the NeverSoft repository. We intentionally omit:
# - command-not-found (not required for shell operation)
# - termux-tools / termux-am / termux-am-socket (NeverSoft owns its pkg wrapper)
# - Termux's optional editor/network convenience bundle
text = bootstrap_build.read_text()
text = text.replace('PACKAGES+=("bzip2")', 'PACKAGES+=("libbz2")', 1)
text = re.sub(r'\n\s*if ! \$\{BOOTSTRAP_ANDROID10_COMPATIBLE\}; then\n\s*PACKAGES\+\=\("command-not-found"\)\n\s*else\n\s*PACKAGES\+\=\("proot"\)\n\s*fi', '', text, count=1)
text = text.replace('\n\t\tPACKAGES+=("termux-tools")', '', 1)

optional_block = '''
\t\t# Additional.
\t\tPACKAGES+=("ed")
\t\tPACKAGES+=("debianutils")
\t\tPACKAGES+=("dos2unix")
\t\tPACKAGES+=("inetutils")
\t\tPACKAGES+=("lsof")
\t\tPACKAGES+=("nano")
\t\tPACKAGES+=("net-tools")
\t\tPACKAGES+=("patch")
\t\tPACKAGES+=("unzip")
'''
if optional_block in text:
    text = text.replace(optional_block, '\n\t\t# NeverSoft essentials beyond the base shell.\n\t\tPACKAGES+=("curl")\n', 1)
elif 'PACKAGES+=("curl")' not in text:
    raise SystemExit("Could not replace optional bootstrap package block")

for forbidden in ('PACKAGES+=("bzip2")', 'PACKAGES+=("command-not-found")', 'PACKAGES+=("termux-tools")'):
    if forbidden in text:
        raise SystemExit(f"Stale bootstrap entry survived: {forbidden}")
for required in ('PACKAGES+=("apt")', 'PACKAGES+=("bash")', 'PACKAGES+=("coreutils")', 'PACKAGES+=("termux-core")', 'PACKAGES+=("termux-exec")', 'PACKAGES+=("curl")', 'PACKAGES+=("libbz2")'):
    if required not in text:
        raise SystemExit(f"Required NeverSoft bootstrap entry missing: {required}")
bootstrap_build.write_text(text)
PY

# Confirm the Termux package build system derives exactly the NeverSoft prefix.
bash -lc "source '$PROPERTIES'; printf '%s\n' \
  \"TERMUX__NAME=\$TERMUX__NAME\" \
  \"TERMUX_APP__PACKAGE_NAME=\$TERMUX_APP__PACKAGE_NAME\" \
  \"TERMUX_APP__DATA_DIR=\$TERMUX_APP__DATA_DIR\" \
  \"TERMUX__ROOTFS=\$TERMUX__ROOTFS\" \
  \"TERMUX__HOME=\$TERMUX__HOME\" \
  \"TERMUX__PREFIX=\$TERMUX__PREFIX\""

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
if grep -Fq 'docbook-xsl' "$APT_BUILD"; then
  echo "ERROR: apt docbook build dependency survived slim bootstrap patch" >&2
  exit 1
fi

printf 'Prepared NeverSoft package tree at %s (upstream %s)\n' "$PKG_DIR" "$RESOLVED_SHA"
