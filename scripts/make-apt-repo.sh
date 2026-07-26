#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

ARCH="${1:-aarch64}"
WORK_DIR="${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}"
PKG_DIR="$WORK_DIR/termux-packages"
OUTPUT_DIR="$PKG_DIR/output"
REPO_ROOT="$ROOT_DIR/artifacts/publish/apt/termux-main"
POOL_DIR="$REPO_ROOT/pool/main"
DIST_DIR="$REPO_ROOT/dists/$NEVERSOFT_APT_SUITE/$NEVERSOFT_APT_COMPONENT/binary-$ARCH"

command -v dpkg-scanpackages >/dev/null 2>&1 || {
  echo "ERROR: dpkg-scanpackages is required on the build host." >&2
  echo "On Debian/Ubuntu: sudo apt-get install dpkg-dev" >&2
  exit 1
}

[[ -d "$OUTPUT_DIR" ]] || { echo "ERROR: package output directory not found: $OUTPUT_DIR" >&2; exit 1; }

rm -rf "$REPO_ROOT"
mkdir -p "$POOL_DIR" "$DIST_DIR"

copied=0
for deb in "$OUTPUT_DIR"/*.deb; do
  [[ -f "$deb" ]] || continue
  pkg_arch="$(dpkg-deb -f "$deb" Architecture 2>/dev/null || true)"
  case "$pkg_arch" in
    "$ARCH"|all)
      cp -f "$deb" "$POOL_DIR/"
      copied=$((copied + 1))
      ;;
  esac
done

if (( copied == 0 )); then
  echo "ERROR: no $ARCH/all .deb files found in $OUTPUT_DIR" >&2
  exit 1
fi

pushd "$REPO_ROOT" >/dev/null
dpkg-scanpackages --arch "$ARCH" pool/main /dev/null > "dists/$NEVERSOFT_APT_SUITE/$NEVERSOFT_APT_COMPONENT/binary-$ARCH/Packages"
gzip -9c "dists/$NEVERSOFT_APT_SUITE/$NEVERSOFT_APT_COMPONENT/binary-$ARCH/Packages" > "dists/$NEVERSOFT_APT_SUITE/$NEVERSOFT_APT_COMPONENT/binary-$ARCH/Packages.gz"

packages_rel="dists/$NEVERSOFT_APT_SUITE/$NEVERSOFT_APT_COMPONENT/binary-$ARCH/Packages"
packages_gz_rel="$packages_rel.gz"
release="dists/$NEVERSOFT_APT_SUITE/Release"
mkdir -p "$(dirname "$release")"

cat > "$release" <<EOF
Origin: NeverSoft
Label: NeverSoft CMD
Suite: $NEVERSOFT_APT_SUITE
Codename: $NEVERSOFT_APT_SUITE
Architectures: $ARCH
Components: $NEVERSOFT_APT_COMPONENT
Description: Native NeverSoft Android package repository
Date: $(LC_ALL=C date -Ru)
SHA256:
 $(sha256sum "$packages_rel" | awk '{print $1}') $(stat -c%s "$packages_rel") $packages_rel
 $(sha256sum "$packages_gz_rel" | awk '{print $1}') $(stat -c%s "$packages_gz_rel") $packages_gz_rel
EOF
popd >/dev/null

echo "Static NeverSoft APT repository generated."
echo "Packages copied: $copied"
echo "Repository root: $REPO_ROOT"
echo "Configured client URL: $NEVERSOFT_APT_REPO_URL"
echo "NOTE: Phase 1 uses [trusted=yes]. Add signing/InRelease before wider distribution."
