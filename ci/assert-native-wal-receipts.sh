#!/bin/sh
# Fail-closed terminal receipt validation for the 15 exact ODB1 guest fixtures.
# This checks the native verifier's OUTPUT CONTRACT, not compiler authenticity.
set -eu
export LC_ALL=C
if [ "$#" -ne 4 ]; then echo 'expected receipt log, commit, tree, binary digest' >&2; exit 2; fi
file="$1"; commit="$2"; tree="$3"; digest="$4"
[ -f "$file" ] && [ -r "$file" ] || { echo 'missing guest receipt log' >&2; exit 2; }
size="$(wc -c < "$file")"
[ "$size" -gt 0 ] && [ "$size" -le 131072 ] || { echo 'unbounded/empty guest receipt log' >&2; exit 2; }
for h in "$commit" "$tree"; do
  [ "${#h}" -eq 40 ] || exit 2
  case "$h" in *[!0123456789abcdef]*|'') exit 2 ;; esac
done
[ "${#digest}" -eq 64 ] || exit 2
case "$digest" in *[!0123456789abcdef]*|'') exit 2 ;; esac
awk -v commit="$commit" -v tree="$tree" -v digest="$digest" '
BEGIN {
  expected[1]="tests/guest/positive/frame-roundtrip.ores"
  expected[2]="tests/guest/negative/frame-rejections.ores"
  expected[3]="tests/guest/positive/frame-boundaries.ores"
  expected[4]="tests/guest/positive/frame-identity-extremes.ores"
  expected[5]="tests/guest/negative/frame-single-byte-flips.ores"
  expected[6]="tests/guest/positive/frame-size-matrix.ores"
  expected[7]="tests/guest/positive/frame-copy-isolation.ores"
  expected[8]="tests/guest/negative/frame-forged-header.ores"
  expected[9]="tests/guest/negative/frame-canonical-identity.ores"
  expected[10]="tests/guest/positive/frame-binary-octets.ores"
  expected[11]="tests/guest/negative/frame-prefix-suffix.ores"
  expected[12]="tests/guest/positive/frame-adler-collision.ores"
  expected[13]="tests/guest/positive/frame-operation-is-not-dedup.ores"
  expected[14]="tests/guest/negative/frame-trailer-each-byte.ores"
  expected[15]="tests/guest/positive/frame-u16-length-endian.ores"
  terminal="GUEST_WAL_CODEC_ONLY_PASS cases=15; NO AUTHENTICATION, NO DURABILITY CLAIM"
}
{
  if (finished) invalid=1
  if (index($0,"SOURCE_COMMIT=")==1) {
    commitCount++; if ($0 != "SOURCE_COMMIT=" commit) invalid=1
  }
  if (index($0,"SOURCE_TREE=")==1) {
    treeCount++; if ($0 != "SOURCE_TREE=" tree) invalid=1
  }
  if (index($0,"COMPILER_BINARY_SHA256=")==1) {
    digestCount++; if ($0 != "COMPILER_BINARY_SHA256=" digest) invalid=1
  }
  if (index($0,"FIXTURE_SHA256=")==1) {
    fixtureCount++
    # The native gate emits a 64-hex-character sha256sum and exact path.
    if (fixtureCount > 15 || length($0) < 81 ||
        substr($0,1,15) != "FIXTURE_SHA256=" ||
        substr($0,80,2) != "  " ||
        substr($0,82) != expected[fixtureCount]) invalid=1
    hex=substr($0,16,64)
    if (length(hex)!=64 || hex ~ /[^0123456789abcdef]/) invalid=1
    # Independently hash the exact pinned checkout fixture: a plausible but
    # forged 64-character checksum must not become guest-execution evidence.
    if (fixtureCount <= 15) {
      command="sha256sum " expected[fixtureCount]
      if ((command | getline actualHashLine) != 1 ||
          $0 != "FIXTURE_SHA256=" actualHashLine) invalid=1
      close(command)
    }
  }
  if (index($0,"GUEST_EXECUTED_PASS ")==1) {
    guestCount++
    if (guestCount > 15 || $0 != "GUEST_EXECUTED_PASS " expected[guestCount] ||
        guestCount > fixtureCount) invalid=1
  }
  if (index($0,"GUEST_WAL_CODEC_ONLY_PASS")==1) {
    summaryCount++
    if ($0 != terminal || guestCount!=15 || fixtureCount!=15) invalid=1
    finished=1
  }
}
END {
  if (invalid || commitCount!=1 || treeCount!=1 || digestCount!=1 ||
      guestCount!=15 || fixtureCount!=15 || summaryCount!=1) exit 1
}' "$file" || { echo 'REJECTED: missing, duplicate, forged or out-of-order native guest receipts' >&2; exit 1; }
echo 'NATIVE_GUEST_RECEIPT_CONTRACT_PASS cases=15; OUTPUT CHECK ONLY'
