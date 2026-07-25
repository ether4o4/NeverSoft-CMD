#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

ARCHITECTURES="${1:-aarch64}"
ADDITIONAL_PACKAGES="${NEVERSOFT_BOOTSTRAP_ADD:-}"
WORK_DIR="${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}"
PKG_DIR="$WORK_DIR/termux-packages"
ARTIFACT_DIR="$ROOT_DIR/artifacts/bootstrap"

"$ROOT_DIR/scripts/prepare-packages.sh"
mkdir -p "$ARTIFACT_DIR"

args=(./scripts/build-bootstraps.sh -f --architectures "$ARCHITECTURES")
if [[ -n "$ADDITIONAL_PACKAGES" ]]; then
  args+=(--add "$ADDITIONAL_PACKAGES")
fi

pushd "$PKG_DIR" >/dev/null
./scripts/run-docker.sh "${args[@]}"
popd >/dev/null

IFS=',' read -ra arches <<< "$ARCHITECTURES"
for arch in "${arches[@]}"; do
  src="$PKG_DIR/bootstrap-${arch}.zip"
  if [[ ! -f "$src" ]]; then
    echo "ERROR: expected bootstrap not generated: $src" >&2
    exit 1
  fi
  cp -f "$src" "$ARTIFACT_DIR/bootstrap-${arch}.zip"
  sha256sum "$ARTIFACT_DIR/bootstrap-${arch}.zip"
done

echo "NeverSoft bootstrap artifact(s): $ARTIFACT_DIR"
