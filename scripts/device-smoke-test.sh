#!/data/data/com.neversoft.shell/files/usr/bin/bash
set -u

EXPECTED_PREFIX="/data/data/com.neversoft.shell/files/usr"
EXPECTED_HOME="/data/data/com.neversoft.shell/files/home"
REPORT="${HOME:-$EXPECTED_HOME}/neversoft-smoke-test.txt"

pass=0
fail=0

check() {
  local name="$1"
  shift
  if "$@"; then
    echo "PASS | $name" | tee -a "$REPORT"
    pass=$((pass + 1))
  else
    echo "FAIL | $name" | tee -a "$REPORT"
    fail=$((fail + 1))
  fi
}

: > "$REPORT"
echo "NeverSoft native smoke test" | tee -a "$REPORT"
date | tee -a "$REPORT"
echo | tee -a "$REPORT"

check "PREFIX is NeverSoft" test "${PREFIX:-}" = "$EXPECTED_PREFIX"
check "HOME is NeverSoft" test "${HOME:-}" = "$EXPECTED_HOME"
check "bash executable exists" test -x "$EXPECTED_PREFIX/bin/bash"
check "apt executable exists" test -x "$EXPECTED_PREFIX/bin/apt"
check "dpkg executable exists" test -x "$EXPECTED_PREFIX/bin/dpkg"
check "coreutils ls works" "$EXPECTED_PREFIX/bin/ls" --version
check "bash runs" "$EXPECTED_PREFIX/bin/bash" --version
check "apt reports version" "$EXPECTED_PREFIX/bin/apt" --version

if [[ -f "$EXPECTED_PREFIX/etc/apt/sources.list" ]]; then
  check "apt source does not use stock Termux" bash -c "! grep -Eq 'packages(-cf)?\\.termux\\.dev|packages\\.termux\\.org' '$EXPECTED_PREFIX/etc/apt/sources.list'"
  echo "APT sources:" | tee -a "$REPORT"
  cat "$EXPECTED_PREFIX/etc/apt/sources.list" | tee -a "$REPORT"
else
  echo "FAIL | apt sources.list missing" | tee -a "$REPORT"
  fail=$((fail + 1))
fi

# Scan the installed prefix for the old absolute runtime prefix. This is the
# most important contamination check for a native side-by-side fork.
echo | tee -a "$REPORT"
echo "Scanning installed prefix for /data/data/com.termux/files/usr ..." | tee -a "$REPORT"
old_hits="$(grep -RIl --binary-files=text '/data/data/com\.termux/files/usr' "$EXPECTED_PREFIX" 2>/dev/null | head -n 100 || true)"
if [[ -z "$old_hits" ]]; then
  echo "PASS | no old Termux runtime prefix found" | tee -a "$REPORT"
  pass=$((pass + 1))
else
  echo "FAIL | old Termux runtime prefix found in:" | tee -a "$REPORT"
  printf '%s\n' "$old_hits" | tee -a "$REPORT"
  fail=$((fail + 1))
fi

echo | tee -a "$REPORT"
echo "Summary: $pass passed, $fail failed" | tee -a "$REPORT"
echo "Report: $REPORT" | tee -a "$REPORT"

(( fail == 0 ))
