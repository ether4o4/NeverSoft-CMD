#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

WORK_DIR="${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}"
APP_DIR="$WORK_DIR/termux-app"
ARTIFACT_DIR="$ROOT_DIR/artifacts/apk"

"$ROOT_DIR/scripts/prepare-app.sh"
"$ROOT_DIR/scripts/stage-bootstrap-for-app.sh"
"$ROOT_DIR/scripts/validate-app-source.sh" "$APP_DIR"

mkdir -p "$ARTIFACT_DIR"

pushd "$APP_DIR" >/dev/null
./gradlew --no-daemon :neversoft-app:assembleDebug
popd >/dev/null

mapfile -t apks < <(find "$APP_DIR/neversoft-app/build/outputs/apk" -type f -name '*.apk' | sort)
if (( ${#apks[@]} == 0 )); then
  echo "ERROR: Gradle completed but no NeverSoft APK was found." >&2
  exit 1
fi

rm -f "$ARTIFACT_DIR"/*.apk
for apk in "${apks[@]}"; do
  name="$(basename "$apk")"
  cp -f "$apk" "$ARTIFACT_DIR/$name"
  sha256sum "$ARTIFACT_DIR/$name"
done

echo "NeverSoft-owned APK artifact(s): $ARTIFACT_DIR"
