#!/usr/bin/env bash
# Real ARM64 package lifecycle verification against the NeverSoft prefix.
#
# What this proves on an x86_64 host WITHOUT Docker, root, or a device:
#   1. The Android NDK cross-compiles a genuine aarch64 ELF into the NeverSoft
#      prefix (not the stock Termux prefix).
#   2. dpkg performs a real install transaction: payload lands under the
#      NeverSoft prefix and the package is registered in a dpkg database.
#   3. A NeverSoft APT repository is generated and `apt-get` resolves, downloads,
#      installs and removes the package through that repo and prefix.
#   4. dpkg removal deregisters the package and deletes its files.
#
# What this does NOT prove: on-device bionic execution. The NDK sysroot libc is
# a link stub, so the cross-compiled binary is ELF/-prefix verified here, not run.
# On-device execution is a separate device-acceptance gate.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

ARCH="${1:-aarch64}"
[[ "$ARCH" == aarch64 ]] || { echo "Only aarch64 is supported by this gate" >&2; exit 2; }
PREFIX_REL="${NEVERSOFT_PREFIX#/}"
WORK="${NEVERSOFT_LIFECYCLE_DIR:-$ROOT_DIR/artifacts/native-lifecycle}"
REPORT_DIR="$ROOT_DIR/artifacts/reports"
REPORT="$REPORT_DIR/native-package-lifecycle.json"
mkdir -p "$WORK" "$REPORT_DIR"

log() { echo "[native-lifecycle] $*"; }
fail() { echo "[native-lifecycle][FAIL] $*" >&2; exit 1; }

# ---- Locate the cross compiler -------------------------------------------------
: "${NEVERSOFT_NDK:=}"
if [[ -z "$NEVERSOFT_NDK" ]]; then
  for candidate in \
    "${ANDROID_NDK_HOME:-}" \
    "${ANDROID_NDK_ROOT:-}" \
    "${ANDROID_HOME:-}/ndk/22.1.7171670" \
    "${ANDROID_SDK_ROOT:-}/ndk/22.1.7171670"; do
    [[ -n "$candidate" && -d "$candidate/toolchains/llvm" ]] && NEVERSOFT_NDK="$candidate" && break
  done
fi
[[ -n "$NEVERSOFT_NDK" && -d "$NEVERSOFT_NDK" ]] || fail "Android NDK not found; set NEVERSOFT_NDK"
API="${NEVERSOFT_API_LEVEL:-28}"
CLANG="$NEVERSOFT_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang"
[[ -x "$CLANG" ]] || fail "aarch64 clang not found at $CLANG"
log "Using NDK clang: $CLANG"

# ---- 1. Cross-compile a native aarch64 binary into the NeverSoft prefix --------
SRC="$WORK/neversoft-probe.c"
cat > "$SRC" <<'C'
#include <stdio.h>
/* Compiled-in prefix proves the binary targets the NeverSoft prefix. */
#ifndef NEVERSOFT_PREFIX
#define NEVERSOFT_PREFIX "unset"
#endif
int main(void) {
    printf("neversoft-probe prefix=%s\n", NEVERSOFT_PREFIX);
    return 0;
}
C
BIN="$WORK/neversoft-probe"
"$CLANG" -O2 -DNEVERSOFT_PREFIX="\"$NEVERSOFT_PREFIX\"" "$SRC" -o "$BIN"
file "$BIN" | tee "$WORK/binary-file.txt"
file "$BIN" | grep -q 'ARM aarch64' || fail "compiled binary is not ARM aarch64"
if grep -aq "/data/data/com.termux" "$BIN"; then fail "native binary embeds stock Termux prefix"; fi
grep -aq "$NEVERSOFT_PREFIX" "$BIN" || fail "native binary does not embed NeverSoft prefix"
log "Cross-compiled aarch64 ELF embeds NeverSoft prefix, not stock Termux."

# ---- 2. Assemble a real .deb with NeverSoft-prefix payload ---------------------
PKGROOT="$WORK/pkgroot"
rm -rf "$PKGROOT"
install -d "$PKGROOT/DEBIAN" "$PKGROOT/$PREFIX_REL/bin"
install -m0755 "$BIN" "$PKGROOT/$PREFIX_REL/bin/neversoft-probe"
cat > "$PKGROOT/$PREFIX_REL/bin/neversoft-probe-shim" <<SHIM
#!$NEVERSOFT_PREFIX/bin/bash
exec "$NEVERSOFT_PREFIX/bin/neversoft-probe" "\$@"
SHIM
chmod 0755 "$PKGROOT/$PREFIX_REL/bin/neversoft-probe-shim"
VERSION="1.0"
cat > "$PKGROOT/DEBIAN/control" <<CTRL
Package: neversoft-probe
Version: $VERSION
Architecture: $ARCH
Maintainer: NeverSoft CI <build@neversoft.invalid>
Installed-Size: 8
Description: NeverSoft native-prefix lifecycle verification package
 Cross-compiled aarch64 probe used to verify dpkg/apt against the
 NeverSoft prefix without any dependency on the stock Termux prefix.
CTRL
DEB="$WORK/neversoft-probe_${VERSION}_${ARCH}.deb"
dpkg-deb --build "$PKGROOT" "$DEB" >/dev/null
log "Built $DEB"
python3 "$ROOT_DIR/scripts/validate-distribution.py" deb "$DEB" \
  --report "$REPORT_DIR/native-probe-package-validation.json"
log "Package passed prefix/arch/symlink validation."

# ---- 3. dpkg install/remove transaction directly against the prefix -----------
export PATH="/sbin:/usr/sbin:$PATH"   # dpkg needs ldconfig/start-stop-daemon
ADMINDIR="$WORK/dpkg-admindir"
INSTROOT="$WORK/instroot"
rm -rf "$ADMINDIR" "$INSTROOT"
mkdir -p "$ADMINDIR/info" "$ADMINDIR/updates" "$INSTROOT"
: > "$ADMINDIR/status"
dpkg --root="$INSTROOT" --admindir="$ADMINDIR" --log="$WORK/dpkg.log" \
  --force-not-root --force-architecture --install "$DEB" >/dev/null
INSTALLED="$INSTROOT/$PREFIX_REL/bin/neversoft-probe"
[[ -f "$INSTALLED" ]] || fail "dpkg install did not place payload under the NeverSoft prefix"
dpkg --root="$INSTROOT" --admindir="$ADMINDIR" -s neversoft-probe >/dev/null \
  || fail "dpkg did not register neversoft-probe"
log "dpkg install: payload under NeverSoft prefix and registered in database."
dpkg --root="$INSTROOT" --admindir="$ADMINDIR" --log="$WORK/dpkg.log" \
  --force-not-root --remove neversoft-probe >/dev/null
[[ -f "$INSTALLED" ]] && fail "dpkg remove left payload behind"
if dpkg --root="$INSTROOT" --admindir="$ADMINDIR" -s neversoft-probe >/dev/null 2>&1; then
  fail "dpkg remove did not deregister neversoft-probe"
fi
log "dpkg remove: payload deleted and package deregistered."

# ---- 4. apt-get through a generated NeverSoft repository -----------------------
REPO="$WORK/apt-repo"
POOL="$REPO/pool/main"
DIST="$REPO/dists/$NEVERSOFT_APT_SUITE/$NEVERSOFT_APT_COMPONENT/binary-$ARCH"
rm -rf "$REPO"
mkdir -p "$POOL" "$DIST"
cp "$DEB" "$POOL/"
if command -v dpkg-scanpackages >/dev/null 2>&1; then
  ( cd "$REPO" && dpkg-scanpackages --arch "$ARCH" pool/main /dev/null > "dists/$NEVERSOFT_APT_SUITE/$NEVERSOFT_APT_COMPONENT/binary-$ARCH/Packages" )
else
  ( cd "$REPO" && printf 'Package: neversoft-probe\nVersion: %s\nArchitecture: %s\nFilename: pool/main/%s\nSize: %s\nSHA256: %s\nDescription: NeverSoft native-prefix lifecycle package\n\n' \
      "$VERSION" "$ARCH" "$(basename "$DEB")" "$(stat -c%s "$POOL/$(basename "$DEB")")" "$(sha256sum "$POOL/$(basename "$DEB")" | awk '{print $1}')" \
      > "dists/$NEVERSOFT_APT_SUITE/$NEVERSOFT_APT_COMPONENT/binary-$ARCH/Packages" )
fi
gzip -9c "$DIST/Packages" > "$DIST/Packages.gz"
( cd "$REPO/dists/$NEVERSOFT_APT_SUITE" && cat > Release <<REL
Origin: NeverSoft
Label: NeverSoft CMD
Suite: $NEVERSOFT_APT_SUITE
Codename: $NEVERSOFT_APT_SUITE
Architectures: $ARCH
Components: $NEVERSOFT_APT_COMPONENT
Date: $(LC_ALL=C date -Ru)
REL
)

APTROOT="$WORK/apt-root"
rm -rf "$APTROOT"
mkdir -p "$APTROOT/etc/apt/apt.conf.d" "$APTROOT/etc/apt/preferences.d" \
  "$APTROOT/var/lib/apt/lists/partial" "$APTROOT/var/cache/apt/archives/partial" \
  "$APTROOT/var/lib/dpkg/info" "$APTROOT/var/lib/dpkg/updates" \
  "$APTROOT/var/log/apt" "$APTROOT$NEVERSOFT_PREFIX"
: > "$APTROOT/var/lib/dpkg/status"
echo "deb [trusted=yes] file:$REPO $NEVERSOFT_APT_SUITE $NEVERSOFT_APT_COMPONENT" \
  > "$APTROOT/etc/apt/sources.list"
APT_OPTS=(
  -o Debug::NoLocking=1
  -o APT::Sandbox::User="$(id -un)"
  -o Dir="$APTROOT"
  -o Dir::State="$APTROOT/var/lib/apt"
  -o Dir::State::status="$APTROOT/var/lib/dpkg/status"
  -o Dir::Cache="$APTROOT/var/cache/apt"
  -o Dir::Etc="$APTROOT/etc/apt"
  -o Dir::Log="$APTROOT/var/log/apt"
  -o Dir::Bin::dpkg="/usr/bin/dpkg"
  -o DPkg::Options::="--root=$APTROOT"
  -o DPkg::Options::="--admindir=$APTROOT/var/lib/dpkg"
  -o DPkg::Options::="--force-not-root"
  -o DPkg::Options::="--force-architecture"
  -o APT::Architecture="$ARCH"
  -o APT::Architectures::="$ARCH"
  -o APT::Get::AllowUnauthenticated=1
)
apt-get "${APT_OPTS[@]}" update >/dev/null 2>"$WORK/apt-update.log" || { cat "$WORK/apt-update.log" >&2; fail "apt-get update failed"; }
apt-cache "${APT_OPTS[@]}" show neversoft-probe >/dev/null 2>&1 || fail "apt could not see neversoft-probe in the NeverSoft repo"
log "apt-get update: NeverSoft repository indexed, package visible to apt."
apt-get "${APT_OPTS[@]}" install -y neversoft-probe >/dev/null 2>"$WORK/apt-install.log" || { cat "$WORK/apt-install.log" >&2; fail "apt-get install failed"; }
APT_INSTALLED="$APTROOT/$PREFIX_REL/bin/neversoft-probe"
[[ -f "$APT_INSTALLED" ]] || fail "apt install did not place payload under the NeverSoft prefix"
log "apt-get install: resolved from repo and installed into NeverSoft prefix."
apt-get "${APT_OPTS[@]}" remove -y neversoft-probe >/dev/null 2>"$WORK/apt-remove.log" || { cat "$WORK/apt-remove.log" >&2; fail "apt-get remove failed"; }
[[ -f "$APT_INSTALLED" ]] && fail "apt remove left payload behind"
log "apt-get remove: payload removed from NeverSoft prefix."

# ---- Report --------------------------------------------------------------------
DEB_SHA="$(sha256sum "$DEB" | awk '{print $1}')"
cat > "$REPORT" <<JSON
{
  "arch": "$ARCH",
  "prefix": "$NEVERSOFT_PREFIX",
  "ndk": "$NEVERSOFT_NDK",
  "api_level": $API,
  "package": "$(basename "$DEB")",
  "package_sha256": "$DEB_SHA",
  "checks": {
    "cross_compiled_aarch64": true,
    "embeds_neversoft_prefix": true,
    "embeds_stock_termux_prefix": false,
    "dpkg_install_registered": true,
    "dpkg_remove_deregistered": true,
    "apt_update_indexed": true,
    "apt_install_into_prefix": true,
    "apt_remove_from_prefix": true
  },
  "on_device_bionic_execution_verified": false,
  "note": "Host cross-build + real dpkg/apt transactions against the NeverSoft prefix. On-device bionic execution is a separate device-acceptance gate."
}
JSON
log "Wrote $REPORT"
echo "[native-lifecycle] PASS: aarch64 build + dpkg/apt install+remove against $NEVERSOFT_PREFIX"
