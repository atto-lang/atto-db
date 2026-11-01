#!/bin/sh
# Run against a separately provisioned and independently attested Oreslang
# compiler; never substitute a host simulation or auto-build from another org.
set -eu
export LC_ALL=C
: "${ORESLANG_BIN:?absolute path to executable Oreslang compiler required}"
: "${ORESLANG_COMPILER_SHA256:?independently pinned executable SHA256 required}"
: "${DB_EXPECTED_COMMIT:?independent exact source commit pin required}"
: "${DB_EXPECTED_TREE:?independent exact source tree pin required}"
case "$ORESLANG_BIN" in /*) ;; *) echo "compiler path must be absolute" >&2; exit 2;; esac
test -x "$ORESLANG_BIN"
case "$ORESLANG_COMPILER_SHA256" in
  *[!0123456789abcdef]*|"") echo "bad compiler digest" >&2; exit 2 ;;
esac
test "${#ORESLANG_COMPILER_SHA256}" -eq 64
for digest in "$DB_EXPECTED_COMMIT" "$DB_EXPECTED_TREE"; do
  case "$digest" in *[!0123456789abcdef]*|"") echo "bad source pin" >&2; exit 2;; esac
  test "${#digest}" -eq 40 || { echo "source pin must be 40 hex chars" >&2; exit 2; }
done
printf '%s  %s\n' "$ORESLANG_COMPILER_SHA256" "$ORESLANG_BIN" | sha256sum -c - >/dev/null
command -v timeout >/dev/null 2>&1
test "$(pwd -P)" = "$(git rev-parse --show-toplevel)" || {
  echo "must run from repository root" >&2; exit 2;
}
test -z "$(git status --porcelain)" || { echo "dirty checkout" >&2; exit 2; }
actual_commit="$(git rev-parse HEAD)"
actual_tree="$(git rev-parse 'HEAD^{tree}')"
test "$actual_commit" = "$DB_EXPECTED_COMMIT" || {
  echo "source commit pin mismatch" >&2; exit 2;
}
test "$actual_tree" = "$DB_EXPECTED_TREE" || {
  echo "source tree pin mismatch" >&2; exit 2;
}
printf 'SOURCE_COMMIT=%s\nSOURCE_TREE=%s\nCOMPILER_BINARY_SHA256=%s\n' \
  "$actual_commit" "$actual_tree" "$ORESLANG_COMPILER_SHA256"
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
run_case tests/guest/positive/frame-boundaries.ores db:wal-boundaries-ok
run_case tests/guest/positive/frame-identity-extremes.ores db:wal-identities-ok
run_case tests/guest/negative/frame-single-byte-flips.ores db:wal-byte-flips-ok
run_case tests/guest/positive/frame-size-matrix.ores db:wal-size-matrix-ok
run_case tests/guest/positive/frame-copy-isolation.ores db:wal-copy-isolation-ok
run_case tests/guest/negative/frame-forged-header.ores db:wal-forged-header-ok
run_case tests/guest/negative/frame-canonical-identity.ores db:wal-canonical-header-ok
echo 'GUEST_WAL_CODEC_ONLY_PASS cases=9; NO DURABILITY CLAIM'
