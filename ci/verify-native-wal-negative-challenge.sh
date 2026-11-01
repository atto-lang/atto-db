#!/bin/sh
# Fail closed if the purported backend compiler accepts a deliberately corrupt
# copy of a positive ODB1 guest. Host-only admission guard, NOT backend proof.
set -eu
export LC_ALL=C
: "${ORESLANG_BIN:?pinned native compiler executable required}"
: "${ORESLANG_COMPILER_SHA256:?pinned native compiler digest required}"
case "$ORESLANG_BIN" in /*) ;; *) echo 'compiler path not absolute' >&2; exit 2;; esac
case "$ORESLANG_COMPILER_SHA256" in *[!0123456789abcdef]*|'') exit 2;; esac
test "${#ORESLANG_COMPILER_SHA256}" -eq 64
printf '%s  %s\n' "$ORESLANG_COMPILER_SHA256" "$ORESLANG_BIN" | sha256sum -c - >/dev/null
command -v timeout >/dev/null 2>&1
original=tests/guest/positive/frame-roundtrip.ores
test -f "$original"
challenge=$(mktemp 'tests/guest/positive/odb1-invalid-XXXXXXXX.ores')
trap 'rm -f "$challenge"' EXIT HUP INT TERM
# Preserve the positive fixture's import-relative directory and all its code.
# The extra unmatched token sequence cannot be accepted by any valid grammar.
cp "$original" "$challenge"
printf '\nfnc ODB1_SYNTACTICALLY_IMPOSSIBLE( @@@ ??\n' >> "$challenge"
# A timeout, failed exec, or crash is not a lexical/compiler rejection.
# In particular `timeout` uses 124 for deadline, 125-127 for launcher errors,
# and signals generally report 128+.
check_rc=0
timeout 20s "$ORESLANG_BIN" --check "$challenge" >/dev/null 2>&1 || check_rc=$?
if [ "$check_rc" -eq 0 ] || [ "$check_rc" -ge 124 ]; then
  echo "NATIVE_COMPILER_MALFORMED_CHECK_NOT_PROVED status=$check_rc" >&2
  exit 1
fi
run_rc=0
timeout 20s "$ORESLANG_BIN" "$challenge" >/dev/null 2>&1 || run_rc=$?
if [ "$run_rc" -eq 0 ] || [ "$run_rc" -ge 124 ]; then
  echo "NATIVE_COMPILER_MALFORMED_RUN_NOT_PROVED status=$run_rc" >&2
  exit 1
fi
printf '%s\n' 'NATIVE_NEGATIVE_SOURCE_CHALLENGE_PASS (does not prove authentic backend codegen)'
