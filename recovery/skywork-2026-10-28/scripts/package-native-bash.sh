#!/usr/bin/env bash
# Cross-compile a real aarch64 GNU bash for the NeverSoft prefix and package it
# as a .deb, then verify the package with the distribution validator.
#
# Requires: Android NDK clang (NEVERSOFT_NDK / ANDROID_HOME) and a host C
# compiler for bash's build-time helpers (CC_FOR_BUILD, e.g. gcc or a zig cc
# shim). Honest scope: produces and validates a native-prefix bash package on an
# x86_64 host. It does NOT run bash under bionic; that is a device-acceptance gate.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

ARCH="aarch64"
PREFIX_REL="${NEVERSOFT_PREFIX#/}"
WORK="${NEVERSOFT_BASH_DIR:-$ROOT_DIR/artifacts/native-bash}"
REPORT_DIR="$ROOT_DIR/artifacts/reports"
BASH_VERSION_SRC="${NEVERSOFT_BASH_VERSION:-5.2.37}"
BASH_URL="${NEVERSOFT_BASH_URL:-https://deb.debian.org/debian/pool/main/b/bash/bash_${BASH_VERSION_SRC}.orig.tar.xz}"
API="${NEVERSOFT_API_LEVEL:-28}"
mkdir -p "$WORK" "$REPORT_DIR"

fail() { echo "[native-bash][FAIL] $*" >&2; exit 1; }
log() { echo "[native-bash] $*"; }

: "${NEVERSOFT_NDK:=}"
if [[ -z "$NEVERSOFT_NDK" ]]; then
  for candidate in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" \
    "${ANDROID_HOME:-}/ndk/22.1.7171670" "${ANDROID_SDK_ROOT:-}/ndk/22.1.7171670"; do
    [[ -n "$candidate" && -d "$candidate/toolchains/llvm" ]] && NEVERSOFT_NDK="$candidate" && break
  done
fi
[[ -n "$NEVERSOFT_NDK" && -d "$NEVERSOFT_NDK" ]] || fail "Android NDK not found; set NEVERSOFT_NDK"
TC="$NEVERSOFT_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TC/aarch64-linux-android${API}-clang"
[[ -x "$CC" ]] || fail "clang not found: $CC"
command -v "${CC_FOR_BUILD:-gcc}" >/dev/null 2>&1 || fail "host build compiler (CC_FOR_BUILD/gcc) not found"

SRCROOT="$WORK/src"
mkdir -p "$SRCROOT"
TARBALL="$SRCROOT/bash.tar.xz"
if [[ ! -d "$SRCROOT/bash-$BASH_VERSION_SRC" ]]; then
  [[ -f "$TARBALL" ]] || curl -fSL --retry 3 -o "$TARBALL" "$BASH_URL"
  tar -xf "$TARBALL" -C "$SRCROOT"
fi
BUILD="$SRCROOT/bash-$BASH_VERSION_SRC"
[[ -d "$BUILD" ]] || fail "bash source not found at $BUILD"

pushd "$BUILD" >/dev/null
if [[ ! -f bash ]]; then
  CC="$CC" CC_FOR_BUILD="${CC_FOR_BUILD:-gcc}" \
  AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" \
  ./configure --host=aarch64-linux-android --prefix="$NEVERSOFT_PREFIX" \
    --without-bash-malloc --disable-nls \
    bash_cv_getcwd_malloc=yes bash_cv_job_control_missing=present \
    bash_cv_sys_named_pipes=present bash_cv_func_sigsetjmp=present \
    bash_cv_unusable_rtsigs=no > "$WORK/configure.log" 2>&1 \
    || { tail -20 "$WORK/configure.log" >&2; fail "bash configure failed"; }
  CC="$CC" CC_FOR_BUILD="${CC_FOR_BUILD:-gcc}" \
  AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" \
    make -j"$(nproc)" > "$WORK/build.log" 2>&1 \
    || { tail -20 "$WORK/build.log" >&2; fail "bash build failed"; }
fi
"$TC/llvm-strip" bash -o "$WORK/bash" || fail "strip failed"
popd >/dev/null

file "$WORK/bash" | tee "$WORK/bash-file.txt" | grep -q 'ARM aarch64' || fail "bash is not aarch64"
grep -aq "$NEVERSOFT_PREFIX" "$WORK/bash" || fail "bash does not embed NeverSoft prefix"
if grep -aq "/data/data/com.termux" "$WORK/bash"; then fail "bash embeds stock Termux prefix"; fi
NEEDED="$("$TC/llvm-readelf" -d "$WORK/bash" | awk '/NEEDED/{gsub(/[][]/,"",$NF);print $NF}' | sort | tr '\n' ' ')"
log "aarch64 bash NEEDED: $NEEDED"

# Package as a real .deb.
VERSION="${BASH_VERSION_SRC}-neversoft1"
PKGROOT="$WORK/pkgroot"
rm -rf "$PKGROOT"
install -d "$PKGROOT/DEBIAN" "$PKGROOT/$PREFIX_REL/bin"
install -m0755 "$WORK/bash" "$PKGROOT/$PREFIX_REL/bin/bash"
ln -sf bash "$PKGROOT/$PREFIX_REL/bin/sh"
SIZE_KB="$(( ( $(stat -c%s "$WORK/bash") + 1023 ) / 1024 ))"
cat > "$PKGROOT/DEBIAN/control" <<CTRL
Package: bash
Version: $VERSION
Architecture: $ARCH
Maintainer: NeverSoft CI <build@neversoft.invalid>
Installed-Size: $SIZE_KB
Essential: no
Section: shells
Priority: required
Description: GNU Bourne Again SHell, cross-built for the NeverSoft prefix
 Native aarch64 GNU bash linked for $NEVERSOFT_PREFIX with no dependency on the
 stock Termux prefix.
CTRL
DEB="$WORK/bash_${VERSION}_${ARCH}.deb"
dpkg-deb --build "$PKGROOT" "$DEB" >/dev/null
log "Built $DEB"
python3 "$ROOT_DIR/scripts/validate-distribution.py" deb "$DEB" \
  --report "$REPORT_DIR/native-bash-package-validation.json" \
  || fail "native bash package failed distribution validation"

cat > "$REPORT_DIR/native-bash-build.json" <<JSON
{
  "component": "bash",
  "source_version": "$BASH_VERSION_SRC",
  "package": "$(basename "$DEB")",
  "package_sha256": "$(sha256sum "$DEB" | awk '{print $1}')",
  "binary_sha256": "$(sha256sum "$WORK/bash" | awk '{print $1}')",
  "arch": "$ARCH",
  "prefix": "$NEVERSOFT_PREFIX",
  "needed_libraries": "$NEEDED",
  "embeds_neversoft_prefix": true,
  "embeds_stock_termux_prefix": false,
  "on_device_bionic_execution_verified": false
}
JSON
log "PASS: native aarch64 bash built and packaged for $NEVERSOFT_PREFIX"
echo "$DEB"
