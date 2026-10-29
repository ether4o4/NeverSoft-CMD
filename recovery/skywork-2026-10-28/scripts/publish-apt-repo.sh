#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

REPO_DIR="$ROOT_DIR/artifacts/publish/apt/termux-main"
PUBLISH_DIR="${NEVERSOFT_PUBLISH_DIR:-$ROOT_DIR/work/package-publish}"
REMOTE="${NEVERSOFT_GIT_REMOTE:-origin}"
BRANCH="main"

[[ -d "$REPO_DIR/dists" && -d "$REPO_DIR/pool" ]] || {
  echo "ERROR: no generated APT repository. Run scripts/make-apt-repo.sh first." >&2
  exit 1
}

rm -rf "$PUBLISH_DIR"
git clone --no-checkout "$(git -C "$ROOT_DIR" remote get-url "$REMOTE")" "$PUBLISH_DIR"
pushd "$PUBLISH_DIR" >/dev/null

git checkout -B "$BRANCH" "origin/$BRANCH"

mkdir -p apt/termux-main
rm -rf apt/termux-main/*
cp -a "$REPO_DIR"/. apt/termux-main/

cat > apt/termux-main/README.md <<EOF
# NeverSoft package repository

This directory is generated. Do not hand-edit package metadata.

Client source:

    deb [trusted=yes] $NEVERSOFT_APT_REPO_URL $NEVERSOFT_APT_SUITE $NEVERSOFT_APT_COMPONENT

Phase 1 is unsigned for personal testing. Signing replaces trusted=yes before wider distribution.
EOF

git add -A
if git diff --cached --quiet; then
  echo "No package repository changes to publish."
  popd >/dev/null
  exit 0
fi

git commit -m "Publish NeverSoft APT repository"
git push origin "$BRANCH"
popd >/dev/null

echo "Published NeverSoft APT repository into '$BRANCH/apt/termux-main'."
