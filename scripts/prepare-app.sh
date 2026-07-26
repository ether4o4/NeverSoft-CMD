#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/neversoft.env"

WORK_DIR="${NEVERSOFT_WORK_DIR:-$ROOT_DIR/work}"
APP_DIR="$WORK_DIR/termux-app"

mkdir -p "$WORK_DIR"

if [[ ! -d "$APP_DIR/.git" ]]; then
  rm -rf "$APP_DIR"
  git clone --depth 1 --branch "$NEVERSOFT_TERMUX_APP_REF" \
    "$NEVERSOFT_TERMUX_APP_REPO" "$APP_DIR"
else
  git -C "$APP_DIR" fetch --depth 1 origin "$NEVERSOFT_TERMUX_APP_REF"
  git -C "$APP_DIR" reset --hard FETCH_HEAD
  git -C "$APP_DIR" clean -fdx
fi

python3 - "$APP_DIR" "$NEVERSOFT_APP_ID" "$NEVERSOFT_APP_NAME" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
app_id = sys.argv[2]
app_name = sys.argv[3]


def replace_exact(path: Path, old: str, new: str, expected_min=1):
    text = path.read_text()
    count = text.count(old)
    if count < expected_min:
        raise SystemExit(f"{path}: expected at least {expected_min} occurrence(s) of {old!r}, found {count}")
    path.write_text(text.replace(old, new))
    print(f"patched {path.relative_to(root)} ({count})")

# Android package identity and visible app name.
gradle = root / "app/build.gradle"
replace_exact(gradle, 'applicationId "com.termux"', f'applicationId "{app_id}"')
replace_exact(gradle, 'manifestPlaceholders.TERMUX_PACKAGE_NAME = "com.termux"', f'manifestPlaceholders.TERMUX_PACKAGE_NAME = "{app_id}"')
replace_exact(gradle, 'manifestPlaceholders.TERMUX_APP_NAME = "Termux"', f'manifestPlaceholders.TERMUX_APP_NAME = "{app_name}"')

# Phase 1 is ARM64-only. This removes the requirement for the other three
# bootstrap archives while proving the native prefix on modern Android first.
text = gradle.read_text()
text, n1 = re.subn(r"include 'x86', 'x86_64', 'armeabi-v7a', 'arm64-v8a'", "include 'arm64-v8a'", text, count=1)
text, n2 = re.subn(r"universalApk true", "universalApk false", text, count=1)
if n1 != 1 or n2 != 1:
    raise SystemExit("Could not restrict Phase 1 APK to arm64-v8a")
text = text.replace('new File("termux-app_"', 'new File("neversoft-cmd_"')

# Never download a stock com.termux bootstrap into the fork. The build must
# consume the locally staged NeverSoft aarch64 bootstrap and fail otherwise.
pattern = re.compile(r'task downloadBootstraps\(\) \{\s*doLast \{.*?\n\s*\}\s*\}', re.S)
replacement = '''task downloadBootstraps() {
    doLast {
        def file = new File(projectDir, "src/main/cpp/bootstrap-aarch64.zip")
        if (!file.exists()) {
            throw new GradleException("Missing NeverSoft bootstrap-aarch64.zip. Run scripts/build-bootstrap.sh and scripts/stage-bootstrap-for-app.sh first.")
        }
        logger.quiet("Using local NeverSoft bootstrap: " + file)
    }
}'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit("Could not replace Termux downloadBootstraps task")
gradle.write_text(text)

# Resource entities used for labels and permission text.
strings = root / "app/src/main/res/values/strings.xml"
replace_exact(strings, '<!ENTITY TERMUX_PACKAGE_NAME "com.termux">', f'<!ENTITY TERMUX_PACKAGE_NAME "{app_id}">')
replace_exact(strings, '<!ENTITY TERMUX_APP_NAME "Termux">', f'<!ENTITY TERMUX_APP_NAME "{app_name}">')

# Android launcher shortcuts use a literal targetPackage and do not accept the
# applicationId placeholder in this Termux version.
shortcuts = root / "app/src/main/res/xml/shortcuts.xml"
replace_exact(shortcuts, 'android:targetPackage="com.termux"', f'android:targetPackage="{app_id}"', expected_min=3)

# Shared runtime constants drive the expected app-private directory and prefix.
constants = root / "termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java"
replace_exact(constants, 'public static final String TERMUX_APP_NAME = "Termux";', f'public static final String TERMUX_APP_NAME = "{app_name}";')
replace_exact(constants, 'public static final String TERMUX_PACKAGE_NAME = "com.termux";', f'public static final String TERMUX_PACKAGE_NAME = "{app_id}";')

print("NeverSoft app source identity patch complete")
PY

"$ROOT_DIR/scripts/validate-app-source.sh" "$APP_DIR"
echo "Prepared NeverSoft app source at: $APP_DIR"
