#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$ROOT_DIR/artifacts/reports"
LOG_FILE="$REPORT_DIR/phase1-build.log"

mkdir -p "$REPORT_DIR"
: > "$LOG_FILE"
exec > >(tee -a "$LOG_FILE") 2>&1

echo "NeverSoft legacy compatibility baseline build (NOT native-prefix acceptance)"
echo "Started: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "Branch/commit: ${GITHUB_REF_NAME:-local}/${GITHUB_SHA:-unknown}"
echo

"$ROOT_DIR/scripts/build-bootstrap.sh" aarch64
"$ROOT_DIR/scripts/build-app.sh"

echo
echo "Compatibility APK compiled. Milestone 1 is NOT complete."
echo "Run build-native-bootstrap.sh and the device acceptance tests before claiming an independent package ecosystem."
