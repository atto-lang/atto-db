#!/bin/sh
# Stage 5: compiler-backed native guest transaction BOOKKEEPING, not DB commit.
# Inherit exact independently pinned checkout/tree/compiler binary verification.
set -eu
export LC_ALL=C
sh ci/verify-native-wal-actor-stage4.sh
: "${ORESLANG_BIN:?independently attested compiler required}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
for source in src/transaction/single_record_gate.ores src/transaction/transaction_actor.ores; do
  if ! timeout 20s "$ORESLANG_BIN" --check "$source" >"$tmp/check.out" 2>"$tmp/check.err"; then
    echo "FAILED transaction source check: $source" >&2
    cat "$tmp/check.err" >&2
    exit 1
  fi
  echo "TRANSACTION_SOURCE_CHECK_PASS $source"
done
run_case() {
  file="$1"
  expected="$2"
  printf 'TRANSACTION_FIXTURE_SHA256='
  sha256sum "$file"
  if ! timeout 20s "$ORESLANG_BIN" --check "$file" >"$tmp/check.out" 2>"$tmp/check.err"; then
    echo "FAILED transaction fixture compile: $file" >&2
    cat "$tmp/check.err" >&2
    exit 1
  fi
  if ! timeout 20s "$ORESLANG_BIN" "$file" >"$tmp/actual" 2>"$tmp/guest.err"; then
    echo "FAILED transaction guest execution: $file" >&2
    cat "$tmp/guest.err" >&2
    exit 1
  fi
  printf '%s' "$expected" >"$tmp/expected"
  if ! cmp -s "$tmp/actual" "$tmp/expected"; then
    echo "FAILED transaction exact stdout: $file" >&2
    cat "$tmp/guest.err" >&2
    exit 1
  fi
  echo "TRANSACTION_GUEST_EXECUTED_PASS $file"
}
run_case tests/guest/positive/single-record-txn-lifecycle.ores db:single-txn-lifecycle-ok
run_case tests/guest/negative/single-record-txn-rejections.ores db:single-txn-rejections-ok
run_case tests/guest/negative/dispatch-handoff-fence.ores db:dispatch-fence-ok
echo 'SINGLE_RECORD_TXN_BOOKKEEPING_ONLY; NO AUTHENTICATED COMMIT, WAL REPLAY OR DURABILITY CLAIM'
