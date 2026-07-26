#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$ROOT_DIR/artifacts/reports"
LOG_FILE="$REPORT_DIR/phase1-build.log"

mkdir -p "$REPORT_DIR"
: > "$LOG_FILE"
exec > >(tee -a "$LOG_FILE") 2>&1

echo "NeverSoft Phase 1 native build"
echo "Started: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "Branch/commit: ${GITHUB_REF_NAME:-local}/${GITHUB_SHA:-unknown}"
echo

"$ROOT_DIR/scripts/build-bootstrap.sh" aarch64
"$ROOT_DIR/scripts/build-app.sh"

echo
echo "Phase 1 complete: custom ARM64 bootstrap + NeverSoft APK built."
echo "Next: install on an ARM64 Android device alongside stock Termux and run the smoke-test checklist in docs/PHASE-1-NATIVE-BASE.md."
