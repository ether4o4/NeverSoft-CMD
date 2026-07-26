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

# Do NOT pass -f here. The upstream build-bootstraps.sh force path can issue
# destructive cleanup commands when legacy build-directory variables are empty
# in newer termux-packages revisions. NeverSoft's prepare-packages.sh already
# resets and cleans the package tree before each run, so a forced rebuild is
# unnecessary for CI and normal clean builds.
args=(./scripts/build-bootstraps.sh --architectures "$ARCHITECTURES")
if [[ -n "$ADDITIONAL_PACKAGES" ]]; then
  args+=(--add "$ADDITIONAL_PACKAGES")
fi

pushd "$PKG_DIR" >/dev/null
./scripts/run-docker.sh "${args[@]}"
popd >/dev/null

inject_neversoft_helpers() {
  local archive="$1"
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  unzip -q "$archive" -d "$tmp"
  mkdir -p "$tmp/bin"

  cat > "$tmp/bin/ghget" <<'EOF'
#!/data/data/com.neversoft.shell/files/usr/bin/bash
set -euo pipefail

usage() {
  cat <<'HELP'
Usage: ghget OWNER/REPO [DESTINATION] [REF]

Downloads a GitHub repository into the NeverSoft filesystem without requiring
Git. If REF is omitted, GitHub's default branch is used. Set GITHUB_TOKEN for
private repositories or higher API rate limits.

Examples:
  ghget ether4o4/shell-ai-scripts
  ghget owner/repo ~/projects/repo main
HELP
}

[[ $# -ge 1 && $# -le 3 ]] || { usage >&2; exit 2; }
repo="${1#https://github.com/}"
repo="${repo%.git}"
[[ "$repo" == */* ]] || { echo "ghget: expected OWNER/REPO" >&2; exit 2; }
name="${repo##*/}"
dest="${2:-$PWD/$name}"
ref="${3:-}"

if [[ -e "$dest" ]] && [[ -n "$(ls -A "$dest" 2>/dev/null || true)" ]]; then
  echo "ghget: destination is not empty: $dest" >&2
  exit 1
fi
mkdir -p "$dest"

url="https://api.github.com/repos/$repo/tarball"
[[ -n "$ref" ]] && url="$url/$ref"
tmp="$(mktemp "${TMPDIR:-/tmp}/neversoft-gh.XXXXXX.tar.gz")"
cleanup() { rm -f "$tmp"; }
trap cleanup EXIT INT TERM

curl_args=(-fL --retry 3 --retry-delay 1 -H 'Accept: application/vnd.github+json')
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  curl_args+=(-H "Authorization: Bearer $GITHUB_TOKEN")
fi

echo "Fetching $repo${ref:+ @ $ref} ..."
curl "${curl_args[@]}" "$url" -o "$tmp"
tar -xzf "$tmp" -C "$dest" --strip-components=1
printf 'Ready: %s\n' "$dest"
EOF

  cat > "$tmp/bin/github-install" <<'EOF'
#!/data/data/com.neversoft.shell/files/usr/bin/bash
exec ghget "$@"
EOF

  cat > "$tmp/bin/storage-setup" <<'EOF'
#!/data/data/com.neversoft.shell/files/usr/bin/bash
set -euo pipefail

shared="${EXTERNAL_STORAGE:-/storage/emulated/0}"
base="$HOME/storage"
mkdir -p "$base"

link() {
  local name="$1" target="$2"
  rm -f "$base/$name"
  ln -s "$target" "$base/$name"
}

link shared "$shared"
link downloads "$shared/Download"
link documents "$shared/Documents"
link dcim "$shared/DCIM"
link pictures "$shared/Pictures"
link movies "$shared/Movies"
link music "$shared/Music"

echo "NeverSoft storage links:"
ls -l "$base"
if [[ ! -r "$shared" ]]; then
  echo
  echo "Shared storage is not readable yet. Grant Files/Storage permission to NeverSoft CMD and run storage-setup again." >&2
fi
EOF

  chmod 0755 "$tmp/bin/ghget" "$tmp/bin/github-install" "$tmp/bin/storage-setup"

  # Recreate archive with NeverSoft helpers included. The upstream SYMLINKS.txt
  # file remains untouched; BootstrapInstaller reconstructs those links on-device.
  (cd "$tmp" && zip -qr9 "$archive.new" .)
  mv -f "$archive.new" "$archive"

  unzip -Z1 "$archive" | grep -Fxq 'bin/bash' || { echo "ERROR: bootstrap lost bin/bash" >&2; exit 1; }
  unzip -Z1 "$archive" | grep -Fxq 'bin/curl' || { echo "ERROR: bootstrap does not contain curl" >&2; exit 1; }
  unzip -Z1 "$archive" | grep -Fxq 'bin/ghget' || { echo "ERROR: bootstrap helper ghget missing" >&2; exit 1; }
  unzip -Z1 "$archive" | grep -Fxq 'bin/storage-setup' || { echo "ERROR: bootstrap storage helper missing" >&2; exit 1; }
}

IFS=',' read -ra arches <<< "$ARCHITECTURES"
for arch in "${arches[@]}"; do
  src="$PKG_DIR/bootstrap-${arch}.zip"
  if [[ ! -f "$src" ]]; then
    echo "ERROR: expected bootstrap not generated: $src" >&2
    exit 1
  fi
  inject_neversoft_helpers "$src"
  cp -f "$src" "$ARTIFACT_DIR/bootstrap-${arch}.zip"
  sha256sum "$ARTIFACT_DIR/bootstrap-${arch}.zip"
done

echo "NeverSoft bootstrap artifact(s): $ARTIFACT_DIR"
