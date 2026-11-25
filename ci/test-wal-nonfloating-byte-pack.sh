#!/bin/sh
# Source-shape and POSIX shell arithmetic oracle; NOT an Oreslang compiler run.
set -eu
export LC_ALL=C
for file in \
  src/wal/frame.ores \
  tests/guest/negative/frame-canonical-identity.ores \
  tests/guest/negative/frame-forged-header.ores \
  tests/guest/negative/frame-trailer-each-byte.ores; do
  test -f "$file" || { echo "missing byte packing source: $file" >&2; exit 1; }
  if grep -nE '[[:space:]]/[[:space:]]*[0-9]+' "$file"; then
    echo "source int/float byte packing forbidden: $file" >&2
    exit 1
  fi
done
grep -q 'number >> 8' src/wal/frame.ores
grep -q 'rest >> 8' src/wal/frame.ores
for n in 0 1 255 256 257 65520 65535 2147483647; do
  hi=$((n >> 8))
  lo=$((n - hi * 256))
  test "$hi" -eq "$((n / 256))" || exit 1
  test "$lo" -eq "$((n % 256))" || exit 1
  test "$lo" -ge 0 && test "$lo" -le 255
  test "$hi" -ge 0
done
# Make sure the guard catches the old invalid source shape.
if ! printf 'rest = rest / 256;\n' | grep -Eq '[[:space:]]/[[:space:]]*[0-9]+'; then
  echo 'byte packing source guard is ineffective' >&2
  exit 1
fi
echo 'HOST_WAL_INTEGER_PACKING_ORACLE_PASS vectors=8; SOURCE SHAPE ONLY; NO GUEST COMPILER'
