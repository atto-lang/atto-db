#!/bin/sh
# Exact-HEAD native .ores integrity-scanner gate. No host simulator.
set -eu
export LC_ALL=C
: "${ORESLANG_BIN:?absolute independently proven compiler binary required}"
: "${ORESLANG_COMPILER_SHA256:?independent compiler binary hash required}"
: "${DB_EXPECTED_COMMIT:?exact independent source commit required}"
: "${DB_EXPECTED_TREE:?exact independent source tree required}"
# Inherited gate checks clean checkout + commit/tree + compiler binary,
# executes three WAL guests, source checks actor and runs admission guest.
sh ci/verify-native-storage-stage1.sh
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
run_case() {
  file="$1"
  oracle="$2"
  printf 'REPLAY_FIXTURE_SHA256='
  sha256sum "$file"
  if ! timeout 20s "$ORESLANG_BIN" --check "$file" >"$tmp/check" 2>"$tmp/check.err"; then
    echo "FAILED replay guest compile: $file" >&2
    cat "$tmp/check.err" >&2
    exit 1
  fi
  if ! timeout 20s "$ORESLANG_BIN" "$file" >"$tmp/actual" 2>"$tmp/guest.err"; then
    echo "FAILED replay guest execution: $file" >&2
    cat "$tmp/guest.err" >&2
    exit 1
  fi
  printf '%s' "$oracle" >"$tmp/expected"
  if ! cmp -s "$tmp/actual" "$tmp/expected"; then
    echo "FAILED replay exact stdout: $file" >&2
    cat "$tmp/guest.err" >&2
    exit 1
  fi
  echo "REPLAY_GUEST_EXECUTED_PASS $file"
}
run_case tests/guest/positive/replay-window.ores db:replay-window-ok
run_case tests/guest/negative/replay-window-rejections.ores db:replay-rejections-ok
echo 'REPLAY_WINDOW_GUEST_ONLY_PASS; NO FILESYSTEM, COMMIT, TRUNCATION OR DURABILITY CLAIM'
