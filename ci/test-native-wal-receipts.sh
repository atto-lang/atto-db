#!/bin/sh
# Host-only adversarial tests using deliberately fabricated receipt text.
# No compiler or Oreslang guest is executed by this script.
set -eu
export LC_ALL=C
root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)"
helper="$root/ci/assert-native-wal-receipts.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
commit=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
tree=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
digest=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
cat > "$tmp/fixtures" <<'FIXTURES'
tests/guest/positive/frame-roundtrip.ores
tests/guest/negative/frame-rejections.ores
tests/guest/positive/frame-boundaries.ores
tests/guest/positive/frame-identity-extremes.ores
tests/guest/negative/frame-single-byte-flips.ores
tests/guest/positive/frame-size-matrix.ores
tests/guest/positive/frame-copy-isolation.ores
tests/guest/negative/frame-forged-header.ores
tests/guest/negative/frame-canonical-identity.ores
tests/guest/positive/frame-binary-octets.ores
tests/guest/negative/frame-prefix-suffix.ores
tests/guest/positive/frame-adler-collision.ores
tests/guest/positive/frame-operation-is-not-dedup.ores
tests/guest/negative/frame-trailer-each-byte.ores
tests/guest/positive/frame-u16-length-endian.ores
FIXTURES
{
  printf 'SOURCE_COMMIT=%s\nSOURCE_TREE=%s\nCOMPILER_BINARY_SHA256=%s\n' "$commit" "$tree" "$digest"
  while IFS= read -r f; do
    expected_hash="$(sha256sum "$root/$f" | cut -d ' ' -f1)"
    printf 'FIXTURE_SHA256=%s  %s\n' "$expected_hash" "$f"
    printf 'GUEST_EXECUTED_PASS %s\n' "$f"
  done < "$tmp/fixtures"
  printf '%s\n' 'GUEST_WAL_CODEC_ONLY_PASS cases=15; NO AUTHENTICATION, NO DURABILITY CLAIM'
} > "$tmp/valid"
"$helper" "$tmp/valid" "$commit" "$tree" "$digest" > "$tmp/output"
grep -q '^NATIVE_GUEST_RECEIPT_CONTRACT_PASS cases=15' "$tmp/output"
number=0
must_reject() {
  number=$((number+1))
  if "$helper" "$tmp/bad" "$commit" "$tree" "$digest" > "$tmp/output" 2> "$tmp/error"; then
    echo "HOST_RECEIPT_TEST_FAILURE case=$number" >&2
    exit 1
  fi
  if grep -q 'NATIVE_GUEST_RECEIPT_CONTRACT_PASS' "$tmp/output"; then
    echo "HOST_RECEIPT_FALSE_CREDIT case=$number" >&2
    exit 1
  fi
}
# Duplicate success receipt.
awk '/^GUEST_EXECUTED_PASS / && !once++ {print} {print}' "$tmp/valid" > "$tmp/bad"; must_reject
# Missing one successful fixture.
awk '/^GUEST_EXECUTED_PASS / && !once++ {next} {print}' "$tmp/valid" > "$tmp/bad"; must_reject
# A wrong guest fixture falsely receives success.
sed 's@GUEST_EXECUTED_PASS tests/guest/positive/frame-roundtrip.ores@GUEST_EXECUTED_PASS tests/guest/positive/forged.ores@' "$tmp/valid" > "$tmp/bad"; must_reject
# Wrong source or compiler digest.
sed 's@SOURCE_COMMIT=aaaaaaaa@SOURCE_COMMIT=eeeeeeee@' "$tmp/valid" > "$tmp/bad"; must_reject
sed 's@SOURCE_TREE=bbbbbbbb@SOURCE_TREE=eeeeeeee@' "$tmp/valid" > "$tmp/bad"; must_reject
sed 's@COMPILER_BINARY_SHA256=cccccccc@COMPILER_BINARY_SHA256=eeeeeeee@' "$tmp/valid" > "$tmp/bad"; must_reject
# Fake summary alone cannot be counted as execution.
printf '%s\n' 'GUEST_WAL_CODEC_ONLY_PASS cases=15; NO AUTHENTICATION, NO DURABILITY CLAIM' > "$tmp/bad"; must_reject
# Duplicate summary, deleted summary, summary before last receipt.
cp "$tmp/valid" "$tmp/bad"; tail -n 1 "$tmp/valid" >> "$tmp/bad"; must_reject
sed '$d' "$tmp/valid" > "$tmp/bad"; must_reject
{ sed '$d' "$tmp/valid"; printf '%s\n' 'GUEST_WAL_CODEC_ONLY_PASS cases=15; NO AUTHENTICATION, NO DURABILITY CLAIM'; printf 'forged-final-line\n'; } > "$tmp/bad"; must_reject
# Correctly shaped but incorrect fixture hash must be rejected even though
# it is 64 lowercase hex characters.
sed '0,/^FIXTURE_SHA256=/s/^FIXTURE_SHA256=[0-9a-f]\{64\}/FIXTURE_SHA256=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee/' "$tmp/valid" > "$tmp/bad"; must_reject
cp "$tmp/valid" "$tmp/bad"; sed -i 's/^FIXTURE_SHA256=[0-9a-f]/FIXTURE_SHA256=z/' "$tmp/bad"; must_reject
cp "$tmp/valid" "$tmp/bad"; sed -i 's/^FIXTURE_SHA256=[0-9a-f]\{64\}/FIXTURE_SHA256=ddddddd/' "$tmp/bad"; must_reject
# Out-of-order fixture announcement and duplicate file-hash rows.
awk '/^FIXTURE_SHA256=/ && !once++ {print; print; next} {print}' "$tmp/valid" > "$tmp/bad"; must_reject
sed '0,/tests\/guest\/positive\/frame-roundtrip.ores/s@tests/guest/positive/frame-roundtrip.ores@tests/guest/positive/forged.ores@' "$tmp/valid" > "$tmp/bad"; must_reject
# Any trailing log lines after a terminal success are rejected.
cp "$tmp/valid" "$tmp/bad"; printf 'unexpected-tail\n' >> "$tmp/bad"; must_reject
# Excessively large child output should not be trusted or scanned indefinitely.
cp "$tmp/valid" "$tmp/bad"; head -c 135000 /dev/zero | tr '\0' 'x' >> "$tmp/bad"; must_reject
# Wrong argument pin must fail closed.
if "$helper" "$tmp/valid" "$commit" "$tree" short-hash >/dev/null 2>&1; then exit 1; fi
printf 'HOST_NATIVE_GUEST_RECEIPT_REJECTION_PASS negative_cases=%s; MOCK LOGS ONLY\n' "$number"
