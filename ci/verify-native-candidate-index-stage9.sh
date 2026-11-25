#!/bin/sh
# Stage 9 compiler-backed candidate-only ODB1 replay index (no commit/ACK).
# Inherits independent exact Git HEAD/tree/compiler SHA check and earlier fixtures.
set -eu
export LC_ALL=C
sh ci/verify-native-single-record-txn-stage5.sh
: "${ORESLANG_BIN:?independently attested Oreslang executable required}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
source=src/shard/candidate_index.ores
if ! timeout 20s "$ORESLANG_BIN" --check "$source" >"$tmp/check.out" 2>"$tmp/check.err"; then
  echo "FAILED candidate index source compiler check: $source" >&2
  cat "$tmp/check.err" >&2
  exit 1
fi
echo "CANDIDATE_INDEX_SOURCE_CHECK_PASS $source"
verify_case() {
  file="$1"
  expected="$2"
  printf 'CANDIDATE_INDEX_FIXTURE_SHA256='
  sha256sum "$file"
  if ! timeout 20s "$ORESLANG_BIN" --check "$file" >"$tmp/check.out" 2>"$tmp/check.err"; then
    echo "FAILED candidate index fixture compiler check: $file" >&2
    cat "$tmp/check.err" >&2
    exit 1
  fi
  if ! timeout 30s "$ORESLANG_BIN" "$file" >"$tmp/actual" 2>"$tmp/guest.err"; then
    echo "FAILED candidate index guest execution: $file" >&2
    cat "$tmp/guest.err" >&2
    exit 1
  fi
  printf '%s' "$expected" >"$tmp/expected"
  if ! cmp -s "$tmp/expected" "$tmp/actual"; then
    echo "FAILED candidate index byte-exact stdout: $file" >&2
    cat "$tmp/guest.err" >&2
    exit 1
  fi
  echo "CANDIDATE_INDEX_GUEST_EXECUTED_PASS $file"
}
verify_case tests/guest/positive/candidate-index-lifecycle.ores db:candidate-index-lifecycle-ok
verify_case tests/guest/negative/candidate-index-rejections.ores db:candidate-index-rejections-ok
verify_case tests/guest/negative/candidate-index-wal-budget.ores db:candidate-index-wal-budget-ok
echo 'CANDIDATE_ONLY; NO ACTOR STORAGE AUTHORITY, COMMIT ACK, FSYNC OR RECOVERY PROOF'
