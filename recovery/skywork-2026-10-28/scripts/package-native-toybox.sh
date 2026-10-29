#!/usr/bin/env bash
# Cross-compile toybox (a self-contained coreutils/userland) for the NeverSoft
# prefix, emit its enabled applet list, and package it as a .deb.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

ARCH="aarch64"; PREFIX_REL="${NEVERSOFT_PREFIX#/}"
WORK="${NEVERSOFT_TOYBOX_DIR:-$ROOT_DIR/artifacts/native-toybox}"
REPORT_DIR="$ROOT_DIR/artifacts/reports"
SRC_VERSION="${NEVERSOFT_TOYBOX_VERSION:-0.8.9+dfsg}"
API="${NEVERSOFT_API_LEVEL:-28}"
mkdir -p "$WORK" "$REPORT_DIR"
fail() { echo "[toybox][FAIL] $*" >&2; exit 1; }
log() { echo "[toybox] $*"; }

: "${NEVERSOFT_NDK:=}"
if [[ -z "$NEVERSOFT_NDK" ]]; then
  for c in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" \
    "${ANDROID_HOME:-}/ndk/22.1.7171670" "${ANDROID_SDK_ROOT:-}/ndk/22.1.7171670"; do
    [[ -n "$c" && -d "$c/toolchains/llvm" ]] && NEVERSOFT_NDK="$c" && break
  done
fi
[[ -n "$NEVERSOFT_NDK" && -d "$NEVERSOFT_NDK" ]] || fail "Android NDK not found"
TC="$NEVERSOFT_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TC/aarch64-linux-android${API}-clang"
command -v "${HOSTCC:-gcc}" >/dev/null 2>&1 || fail "host compiler (HOSTCC/gcc) not found"

SRCROOT="$WORK/src"; mkdir -p "$SRCROOT"
TAR="$SRCROOT/toybox.tar.xz"
[[ -d "$SRCROOT/toybox-${SRC_VERSION%%+*}" ]] || {
  [[ -f "$TAR" ]] || curl -fSL --retry 3 -o "$TAR" \
    "https://deb.debian.org/debian/pool/main/t/toybox/toybox_${SRC_VERSION}.orig.tar.xz"
  tar -xf "$TAR" -C "$SRCROOT"
}
BUILD="$SRCROOT/toybox-${SRC_VERSION%%+*}"
[[ -d "$BUILD" ]] || fail "toybox source not found"

pushd "$BUILD" >/dev/null
if [[ ! -f toybox ]]; then
  # Host kconfig must be static: a dynamically linked zig/glibc shim can SIGILL.
  "${HOSTCC:-gcc}" -static -O1 -o kconfig/conf kconfig/conf.c kconfig/zconf.tab.c \
    -DKBUILD_NO_NLS=1 -DPROJECT_NAME='"ToyBox"' || fail "host kconfig build failed"
  touch kconfig/conf
  CC="$CC" HOSTCC="${HOSTCC:-gcc}" STRIP="$TC/llvm-strip" make defconfig > "$WORK/defconfig.log" 2>&1 \
    || { tail -10 "$WORK/defconfig.log" >&2; fail "defconfig failed"; }
  # bionic lacks shadow/crypt/utmpx members; disable those toys.
  sed -i -e 's/^CONFIG_SU=y/# CONFIG_SU is not set/' \
    -e 's/^CONFIG_PASSWD=y/# CONFIG_PASSWD is not set/' \
    -e 's/^CONFIG_LOGIN=y/# CONFIG_LOGIN is not set/' \
    -e 's/^CONFIG_SULOGIN=y/# CONFIG_SULOGIN is not set/' \
    -e 's/^CONFIG_W=y/# CONFIG_W is not set/' \
    -e 's/^CONFIG_WHO=y/# CONFIG_WHO is not set/' \
    -e 's/^CONFIG_LAST=y/# CONFIG_LAST is not set/' .config
  CC="$CC" HOSTCC="${HOSTCC:-gcc}" STRIP="$TC/llvm-strip" make -j"$(nproc)" toybox > "$WORK/build.log" 2>&1 \
    || { tail -20 "$WORK/build.log" >&2; fail "toybox build failed"; }
fi
cp toybox "$WORK/toybox"
popd >/dev/null

file "$WORK/toybox" | tee "$WORK/toybox-file.txt" | grep -q 'ARM aarch64' || fail "toybox not aarch64"
grep -aq "/data/data/com.termux" "$WORK/toybox" && fail "toybox embeds stock Termux prefix"
# Enabled non-symlink applets.
rg -o 'CONFIG_[A-Z0-9_]+=y' "$BUILD/.config" | sed 's/^CONFIG_//; s/=y$//' \
  | tr 'A-Z' 'a-z' | grep -vE '^(binary|toybox|help|toys|lib|android|posix|lsb|freebsd)$' \
  | sort -u > "$WORK/applets.txt"
log "toybox applets: $(wc -l < "$WORK/applets.txt")"

VERSION="${SRC_VERSION}-neversoft1"
PKGROOT="$WORK/pkgroot"; rm -rf "$PKGROOT"
install -d "$PKGROOT/DEBIAN" "$PKGROOT/$PREFIX_REL/bin"
install -m0755 "$WORK/toybox" "$PKGROOT/$PREFIX_REL/bin/toybox"
SIZE_KB="$(( ( $(stat -c%s "$WORK/toybox") + 1023 ) / 1024 ))"
cat > "$PKGROOT/DEBIAN/control" <<CTRL
Package: toybox
Version: $VERSION
Architecture: $ARCH
Maintainer: NeverSoft CI <build@neversoft.invalid>
Installed-Size: $SIZE_KB
Section: utils
Priority: standard
Description: toybox userland, cross-built for the NeverSoft prefix
 Self-contained coreutils/userland applets for the NeverSoft native prefix.
CTRL
DEB="$WORK/toybox_${VERSION}_${ARCH}.deb"
dpkg-deb --build "$PKGROOT" "$DEB" >/dev/null
python3 "$ROOT_DIR/scripts/validate-distribution.py" deb "$DEB" \
  --report "$REPORT_DIR/native-toybox-package-validation.json" || fail "toybox package failed validation"
cat > "$REPORT_DIR/native-toybox-build.json" <<JSON
{
  "component": "toybox",
  "source_version": "$SRC_VERSION",
  "package": "$(basename "$DEB")",
  "package_sha256": "$(sha256sum "$DEB" | awk '{print $1}')",
  "binary_sha256": "$(sha256sum "$WORK/toybox" | awk '{print $1}')",
  "arch": "$ARCH",
  "prefix": "$NEVERSOFT_PREFIX",
  "applet_count": "$(wc -l < "$WORK/applets.txt")",
  "on_device_bionic_execution_verified": false
}
JSON
log "PASS: native aarch64 toybox built and packaged"
echo "$DEB"
