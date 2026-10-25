#!/usr/bin/env bash
# Restore the real custom-prefix build path; never fall back to stock archives.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}"
PKG_DIR="$WORK_DIR/termux-packages"
ARTIFACT_DIR="$ROOT_DIR/artifacts/bootstrap"
ARCH="${1:-aarch64}"
[[ "$ARCH" == aarch64 ]] || { echo "Only aarch64 is supported by this gate" >&2; exit 2; }
command -v docker >/dev/null || { echo "Native bootstrap requires Docker on a Linux build host" >&2; exit 1; }
docker info >/dev/null

bash "$ROOT_DIR/scripts/prepare-packages.sh"
# No upstream --force: its legacy cleanup path is unsafe with unset variables.
(cd "$PKG_DIR" && ./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures "$ARCH")
archive="$PKG_DIR/bootstrap-$ARCH.zip"
[[ -s "$archive" ]] || { echo "Native build produced no bootstrap" >&2; exit 1; }
python3 "$ROOT_DIR/scripts/validate-distribution.py" bootstrap "$archive" \
  --report "$ROOT_DIR/artifacts/reports/native-bootstrap-validation.json"
mapfile -d '' packages < <(find "$PKG_DIR/output" -maxdepth 1 -type f -name '*.deb' -print0)
(( ${#packages[@]} > 0 )) || { echo "Native build produced no packages" >&2; exit 1; }
python3 "$ROOT_DIR/scripts/validate-distribution.py" deb "${packages[@]}" \
  --report "$ROOT_DIR/artifacts/reports/native-packages-validation.json"
mkdir -p "$ARTIFACT_DIR"
cp "$archive" "$ARTIFACT_DIR/bootstrap-$ARCH.zip"
sha256sum "$ARTIFACT_DIR/bootstrap-$ARCH.zip"
echo "Native archive checks passed. Device execution and repository signing remain separate gates."
