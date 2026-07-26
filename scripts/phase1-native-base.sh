#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT_DIR/scripts/build-bootstrap.sh" aarch64
"$ROOT_DIR/scripts/build-app.sh"

echo
echo "Phase 1 complete: custom ARM64 bootstrap + NeverSoft APK built."
echo "Next: install on an ARM64 Android device alongside stock Termux and run the smoke-test checklist in docs/PHASE-1-NATIVE-BASE.md."
