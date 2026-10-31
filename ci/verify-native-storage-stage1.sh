#!/bin/sh
# Exact native guest gate for DB storage-owner stage 1.
# Requires compiler executable and independent source commit/tree pins.
# NO DB DURABILITY, FILESYSTEM, AUTHORITY OR CROSS-BACKEND CLAIM.
set -eu
export LC_ALL=C
: "${ORESLANG_BIN:?absolute verified compiler binary is required}"
: "${ORESLANG_COMPILER_SHA256:?verified compiler executable hash required}"
: "${DB_EXPECTED_COMMIT:?independent checkout commit pin required}"
: "${DB_EXPECTED_TREE:?independent checkout tree pin required}"

# Base script checks absolute executable path, binary hash, clean tree,
# independent exact commit/tree and runs the three WAL golden/negative cases.
sh ci/verify-native-wal.sh

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

# The provisional actor syntax is a separate, deliberately fail-closed gate.
# A parser rejection is an implementation blocker, not a successful test.
timeout 20s "$ORESLANG_BIN" --check src/storage/storage_actor.ores \
  >"$tmp/actor.out" 2>"$tmp/actor.err" || {
  echo "FAILED storage actor compile/admission" >&2
  cat "$tmp/actor.err" >&2
  exit 1
}
file=tests/guest/positive/storage-admission-contract.ores
printf 'STORAGE_FIXTURE_SHA256='
sha256sum "$file"
timeout 20s "$ORESLANG_BIN" --check "$file" \
  >"$tmp/check.out" 2>"$tmp/check.err" || {
  echo "FAILED native storage admission fixture compile" >&2
  cat "$tmp/check.err" >&2
  exit 1
}
timeout 20s "$ORESLANG_BIN" "$file" \
  >"$tmp/actual" 2>"$tmp/runtime.err" || {
  echo "FAILED native storage admission fixture execute" >&2
  cat "$tmp/runtime.err" >&2
  exit 1
}
printf '%s' 'db:storage-admission-ok' >"$tmp/expected"
if ! cmp -s "$tmp/expected" "$tmp/actual"; then
  echo 'FAILED exact native storage admission stdout' >&2
  cat "$tmp/runtime.err" >&2
  exit 1
fi
echo 'STORAGE_ADMISSION_GUEST_EXECUTED_PASS cases=1'
echo 'CODEC_PLUS_ADMISSION_ONLY; NO DURABLE_STORAGE_OR_AUTHORITY_CLAIM'
