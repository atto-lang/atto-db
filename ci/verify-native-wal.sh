#!/bin/sh
# Run against a separately provisioned and independently attested Oreslang
# compiler; never substitute a host simulation or auto-build from another org.
set -eu
export LC_ALL=C
: "${ORESLANG_BIN:?absolute path to executable Oreslang compiler required}"
: "${ORESLANG_COMPILER_SHA256:?independently pinned executable SHA256 required}"
case "$ORESLANG_BIN" in /*) ;; *) echo "compiler path must be absolute" >&2; exit 2;; esac
test -x "$ORESLANG_BIN"
case "$ORESLANG_COMPILER_SHA256" in
  *[!0123456789abcdef]*|"") echo "bad compiler digest" >&2; exit 2 ;;
esac
test "${#ORESLANG_COMPILER_SHA256}" -eq 64
printf '%s  %s\n' "$ORESLANG_COMPILER_SHA256" "$ORESLANG_BIN" | sha256sum -c - >/dev/null
command -v timeout >/dev/null 2>&1
test -z "$(git status --porcelain)"
printf 'SOURCE_COMMIT=%s\nSOURCE_TREE=%s\nCOMPILER_BINARY_SHA256=%s\n' \
  "$(git rev-parse HEAD)" "$(git rev-parse HEAD^{tree})" "$ORESLANG_COMPILER_SHA256"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
run_case() {
  file="$1"
  expected="$2"
  printf 'FIXTURE_SHA256='; sha256sum "$file"
  timeout 20s "$ORESLANG_BIN" --check "$file" >"$tmp/check.out" 2>"$tmp/check.err"
  timeout 20s "$ORESLANG_BIN" "$file" >"$tmp/actual" 2>"$tmp/runtime.err"
  printf '%s' "$expected" >"$tmp/expected"
  if ! cmp -s "$tmp/expected" "$tmp/actual"; then
    echo "FAILED exact stdout: $file" >&2
    cat "$tmp/runtime.err" >&2
    exit 1
  fi
  echo "GUEST_EXECUTED_PASS $file"
}
run_case tests/guest/positive/frame-roundtrip.ores db:wal-roundtrip-ok
run_case tests/guest/negative/frame-rejections.ores db:wal-rejections-ok
echo 'GUEST_WAL_CODEC_ONLY_PASS cases=2; NO DURABILITY CLAIM'
