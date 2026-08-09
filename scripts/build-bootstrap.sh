#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/artifacts/bootstrap"
ARCHITECTURES="${1:-aarch64}"

# Proven by OpenClawAndroid/AnyClaw's standalone Android integration. Pin the
# exact bootstrap so NeverSoft builds are fast and reproducible instead of
# rebuilding the full Termux package graph on every CI run.
BOOTSTRAP_VERSION="bootstrap-2026.02.12-r1+apt.android-7"
BASE_URL="https://github.com/termux/termux-packages/releases/download/${BOOTSTRAP_VERSION}"
MIRROR_BASE="https://sourceforge.net/projects/termux-packages.mirror/files/${BOOTSTRAP_VERSION}"

mkdir -p "$ARTIFACT_DIR"

inject_neversoft_helpers() {
  local archive="$1"
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  unzip -q "$archive" -d "$tmp"
  mkdir -p "$tmp/bin"

  cat > "$tmp/bin/ghget" <<'EOF'
#!/data/user/0/com.neversoft.shell/files/usr/bin/bash
set -euo pipefail

usage() {
  cat <<'HELP'
Usage: ghget OWNER/REPO [DESTINATION] [REF]

Downloads a GitHub repository into the NeverSoft filesystem. Full git is also
installable through pkg; ghget exists so a fresh NeverSoft install can pull a
repo immediately.
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

curl "${curl_args[@]}" "$url" -o "$tmp"
tar -xzf "$tmp" -C "$dest" --strip-components=1
printf 'Ready: %s\n' "$dest"
EOF

  cat > "$tmp/bin/github-install" <<'EOF'
#!/data/user/0/com.neversoft.shell/files/usr/bin/bash
exec ghget "$@"
EOF

  cat > "$tmp/bin/storage-setup" <<'EOF'
#!/data/user/0/com.neversoft.shell/files/usr/bin/bash
set -euo pipefail
shared="${EXTERNAL_STORAGE:-/storage/emulated/0}"
base="$HOME/storage"
mkdir -p "$base"
link() { rm -f "$base/$1"; ln -s "$2" "$base/$1"; }
link shared "$shared"
link downloads "$shared/Download"
link documents "$shared/Documents"
link dcim "$shared/DCIM"
link pictures "$shared/Pictures"
link movies "$shared/Movies"
link music "$shared/Music"
ls -l "$base"
EOF

  cat > "$tmp/bin/hf-serve" <<'EOF'
#!/data/user/0/com.neversoft.shell/files/usr/bin/bash
set -euo pipefail
model="${1:-}"
port="${2:-8080}"
[[ -n "$model" ]] || { echo "Usage: hf-serve MODEL.gguf [PORT]" >&2; exit 2; }
[[ -f "$model" ]] || { echo "Model not found: $model" >&2; exit 1; }
exec llama-server -m "$model" --host 127.0.0.1 --port "$port"
EOF

  chmod 0755 "$tmp/bin/ghget" "$tmp/bin/github-install" "$tmp/bin/storage-setup" "$tmp/bin/hf-serve"

  (cd "$tmp" && zip -qr9 "$archive.new" .)
  mv -f "$archive.new" "$archive"

  # List entries once into a variable. Piping `unzip -Z1` straight into
  # `grep -Fxq` makes grep close the pipe on its first match, which sends
  # SIGPIPE to unzip; under `set -o pipefail` that aborts an otherwise valid
  # bootstrap. Reading from a here-string avoids the early-close race.
  local listing
  listing="$(unzip -Z1 "$archive")"
  grep -Fxq 'bin/bash' <<<"$listing" || { echo "ERROR: bootstrap missing bin/bash" >&2; exit 1; }
  grep -Fxq 'bin/apt' <<<"$listing" || { echo "ERROR: bootstrap missing apt" >&2; exit 1; }
  grep -Fxq 'bin/ghget' <<<"$listing" || { echo "ERROR: ghget injection failed" >&2; exit 1; }
}

IFS=',' read -ra arches <<< "$ARCHITECTURES"
for arch in "${arches[@]}"; do
  arch="${arch// /}"
  [[ -n "$arch" ]] || continue
  out="$ARTIFACT_DIR/bootstrap-${arch}.zip"
  url="$BASE_URL/bootstrap-${arch}.zip"
  mirror="$MIRROR_BASE/bootstrap-${arch}.zip/download"

  echo "Downloading pinned Termux bootstrap: $BOOTSTRAP_VERSION / $arch"
  rm -f "$out" "$out.part"
  if curl -fSL --retry 4 --retry-delay 2 -o "$out.part" "$url"; then
    :
  elif curl -fSL --retry 4 --retry-delay 2 -o "$out.part" "$mirror"; then
    :
  else
    rm -f "$out.part"
    echo "ERROR: failed to download bootstrap-$arch.zip" >&2
    exit 1
  fi
  mv "$out.part" "$out"
  inject_neversoft_helpers "$out"
  sha256sum "$out"
done

echo "NeverSoft fast bootstrap artifact(s): $ARTIFACT_DIR"
