#!/usr/bin/env bash
# Assemble a genuine NeverSoft-prefix ARM64 bootstrap from independently built
# native components (bash + toybox), with no stock Termux binaries or paths.
#
# Inputs: the .deb packages produced by package-native-bash.sh (bash) and
# package-native-toybox.sh (toybox). Nothing is copied from a stock bootstrap.
#
# Output: artifacts/bootstrap/bootstrap-aarch64.zip plus a validation report.
# The ZIP mirrors the layout the Android installer expects: prefix files at the
# archive root plus a SYMLINKS.txt using the "target←link" convention.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

WORK="${NEVERSOFT_BOOTSTRAP_WORK:-$ROOT_DIR/artifacts/native-bootstrap}"
ARTIFACT_DIR="$ROOT_DIR/artifacts/bootstrap"
REPORT_DIR="$ROOT_DIR/artifacts/reports"
ZIP="$ARTIFACT_DIR/bootstrap-aarch64.zip"
VERSION="${NEVERSOFT_BOOTSTRAP_VERSION:-0.1.0-native}"
mkdir -p "$WORK" "$ARTIFACT_DIR" "$REPORT_DIR"

fail() { echo "[assemble-bootstrap][FAIL] $*" >&2; exit 1; }
log() { echo "[assemble-bootstrap] $*"; }

BASH_DEB="${NEVERSOFT_BASH_DEB:-$ROOT_DIR/artifacts/native-bash/bash_5.2.37-neversoft1_aarch64.deb}"
TOYBOX_DEB="${NEVERSOFT_TOYBOX_DEB:-$ROOT_DIR/artifacts/native-toybox/toybox_0.8.9-neversoft1_aarch64.deb}"
[[ -f "$BASH_DEB" ]] || fail "bash package missing: $BASH_DEB (run package-native-bash.sh)"
[[ -f "$TOYBOX_DEB" ]] || fail "toybox package missing: $TOYBOX_DEB (run package-native-toybox.sh)"

ROOTFS="$WORK/rootfs"
rm -rf "$ROOTFS"
mkdir -p "$ROOTFS"

extract_prefix() {
  local deb="$1" tmp
  tmp="$(mktemp -d)"
  dpkg-deb -x "$deb" "$tmp"
  # Payload layout is <tmp>/data/data/com.neversoft.shell/files/usr/...
  local src="$tmp/${NEVERSOFT_PREFIX#/}"
  [[ -d "$src" ]] || fail "package $deb lacks NeverSoft prefix payload"
  cp -a "$src/." "$ROOTFS/"
  rm -rf "$tmp"
}
extract_prefix "$BASH_DEB"
extract_prefix "$TOYBOX_DEB"

# ---- Wire toybox applets as bin/<applet> symlinks -----------------------------
BIN="$ROOTFS/bin"
[[ -x "$BIN/bash" ]] || fail "bash not present in assembled rootfs"
[[ -x "$BIN/toybox" ]] || fail "toybox not present in assembled rootfs"

# Derive enabled applets from the toybox binary itself (works without bionic by
# asking the host toybox? no -- instead use the build-time applet list file).
APPLET_LIST="${NEVERSOFT_TOYBOX_APPLETS:-$ROOT_DIR/artifacts/native-toybox/applets.txt}"
[[ -f "$APPLET_LIST" ]] || fail "toybox applet list missing: $APPLET_LIST"

# Standard bin symlinks (never overwrite bash/sh created by bash package).
SYMLINKS_TMP="$WORK/SYMLINKS.generated"
: > "$SYMLINKS_TMP"
# Applet links live only in SYMLINKS.txt (the installer creates them). Do not
# also place on-disk symlinks in the ZIP, or validation flags a collision.
link_applet() {
  local applet="$1" target="$NEVERSOFT_PREFIX/bin/toybox"
  [[ -e "$BIN/$applet" || -L "$BIN/$applet" ]] && return
  printf '%s←bin/%s\n' "$target" "$applet" >> "$SYMLINKS_TMP"
}
while IFS= read -r applet; do
  [[ -n "$applet" ]] || continue
  [[ "$applet" == "sh" || "$applet" == "bash" ]] && continue
  link_applet "$applet"
done < "$APPLET_LIST"

# ---- Minimal prefix skeleton (apt/dpkg state, dirs, config) -------------------
mkdir -p "$ROOTFS/etc/apt/sources.list.d" "$ROOTFS/etc/apt/apt.conf.d" \
  "$ROOTFS/etc/tls" "$ROOTFS/var/lib/dpkg/info" "$ROOTFS/var/lib/dpkg/updates" \
  "$ROOTFS/var/lib/dpkg/triggers" "$ROOTFS/var/lib/apt/lists/partial" \
  "$ROOTFS/var/cache/apt/archives/partial" "$ROOTFS/var/log/apt" \
  "$ROOTFS/tmp" "$ROOTFS/home"
: > "$ROOTFS/var/lib/dpkg/status"

cat > "$ROOTFS/etc/apt/sources.list" <<EOF
# NeverSoft native package repository (populated by CI; no stock Termux repos).
# deb [signed-by=$NEVERSOFT_PREFIX/etc/tls/neversoft.gpg] $NEVERSOFT_APT_REPO_URL $NEVERSOFT_APT_SUITE $NEVERSOFT_APT_COMPONENT
EOF

cat > "$ROOTFS/etc/apt/apt.conf" <<EOF
Dir "/";
Dir::State "$NEVERSOFT_PREFIX/var/lib/apt/";
Dir::State::status "$NEVERSOFT_PREFIX/var/lib/dpkg/status";
Dir::Cache "$NEVERSOFT_PREFIX/var/cache/apt/";
Dir::Log "$NEVERSOFT_PREFIX/var/log/apt/";
Dir::Etc "$NEVERSOFT_PREFIX/etc/apt/";
Dir::Etc::SourceList "$NEVERSOFT_PREFIX/etc/apt/sources.list";
Dir::Etc::SourceParts "$NEVERSOFT_PREFIX/etc/apt/sources.list.d";
Dir::Bin::dpkg "$NEVERSOFT_PREFIX/bin/dpkg";
Dir::Bin::Methods "$NEVERSOFT_PREFIX/lib/apt/methods/";
Dpkg::Options:: "--force-configure-any";
Dpkg::Options:: "--force-bad-path";
Dpkg::Options:: "--instdir=$NEVERSOFT_PREFIX";
EOF

cat > "$ROOTFS/etc/profile" <<'EOF'
# NeverSoft prefix profile
export PREFIX="${PREFIX:-/data/data/com.neversoft.shell/files/usr}"
export HOME="${HOME:-/data/data/com.neversoft.shell/files/home}"
export PATH="$PREFIX/bin:$HOME/go/bin:$HOME/.local/bin:/system/bin:/system/xbin"
export TMPDIR="$PREFIX/tmp"
export TERM="${TERM:-xterm-256color}"
export LANG="${LANG:-en_US.UTF-8}"
[ -f "$PREFIX/etc/profile.d/neversoft.sh" ] && . "$PREFIX/etc/profile.d/neversoft.sh"
EOF
mkdir -p "$ROOTFS/etc/profile.d"
cat > "$ROOTFS/etc/profile.d/neversoft.sh" <<'EOF'
# NeverSoft shell conveniences
export SHELL="${PREFIX}/bin/bash"
export PS1='\[\e[38;5;45m\]neversoft\[\e[0m\]:\w$ '
EOF

# ---- SYMLINKS.txt for directories the app expects -----------------------------
SYMLINKS="$WORK/SYMLINKS.txt"
cat "$SYMLINKS_TMP" > "$SYMLINKS"
# (tmp is a real directory in the native prefix; no symlink needed.)

# ---- Build the bootstrap ZIP --------------------------------------------------
STAGE="$WORK/zipstage"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -a "$ROOTFS/." "$STAGE/"
cp "$SYMLINKS" "$STAGE/SYMLINKS.txt"
# Ensure executables are executable bits (cp -a preserves, but normalize).
find "$STAGE/bin" -type f -maxdepth 1 -exec chmod 0755 {} + 2>/dev/null || true

rm -f "$ZIP"
( cd "$STAGE" && zip -qrX "$ZIP" . )

# ---- Validate: prefix independence + required executables ---------------------
python3 "$ROOT_DIR/scripts/validate-distribution.py" bootstrap "$ZIP" \
  --tier "${NEVERSOFT_BOOTSTRAP_TIER:-core}" \
  --report "$REPORT_DIR/native-bootstrap-validation.json" \
  || fail "assembled bootstrap failed independence validation"

for required in bin/bash bin/toybox SYMLINKS.txt etc/apt/apt.conf; do
  unzip -Z1 "$ZIP" | grep -Fx "$required" >/dev/null || fail "missing $required in bootstrap"
done
# Applet links must be present in SYMLINKS.txt, not as ZIP members.
for applet in ls cat cp mv rm; do
  grep -Fq "←bin/$applet" "$STAGE/SYMLINKS.txt" || fail "applet $applet missing from SYMLINKS.txt"
done

cat > "$REPORT_DIR/native-bootstrap-manifest.json" <<JSON
{
  "bootstrap": "$(basename "$ZIP")",
  "sha256": "$(sha256sum "$ZIP" | awk '{print $1}')",
  "version": "$VERSION",
  "arch": "aarch64",
  "prefix": "$NEVERSOFT_PREFIX",
  "bash_package_sha256": "$(sha256sum "$BASH_DEB" | awk '{print $1}')",
  "toybox_package_sha256": "$(sha256sum "$TOYBOX_DEB" | awk '{print $1}')",
  "applet_count": "$(wc -l < "$APPLET_LIST")",
  "built_from_stock_termux_bootstrap": false,
  "on_device_bionic_execution_verified": false
}
JSON
log "PASS: assembled native NeverSoft bootstrap -> $ZIP"
sha256sum "$ZIP"
