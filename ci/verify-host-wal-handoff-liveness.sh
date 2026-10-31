#!/bin/sh
# Source-provenanced Java21 host regression for independent bounded receipt
# handoffs. This is NOT guest execution, runtime storage authorization,
# replay correctness, or crash/power-loss durability evidence.
set -eu
export LC_ALL=C
# Reuse strict exact-HEAD/tree and independently pinned Java/Javac binaries;
# also run existing WAL 80-assertion and runtime-grant 16-check host gates.
sh ci/verify-host-storage-grant.sh
source=runtime/java/src/org/oreslang/db/primitive/WalFilePrimitive.java
test=runtime/java/test/org/oreslang/db/primitive/WalHandoffLivenessTest.java
bound_test=runtime/java/test/org/oreslang/db/primitive/WalHandoffSaturationTest.java
emergency_test=runtime/java/test/org/oreslang/db/primitive/WalEmergencyQuiescenceTest.java
sha256sum "$source" "$test" "$bound_test" "$emergency_test"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
"$JAVAC_BIN" -Xlint:all -Werror -d "$tmp" "$source" "$test" "$bound_test" "$emergency_test"
if ! timeout 35s "$JAVA_BIN" -cp "$tmp" \
    org.oreslang.db.primitive.WalHandoffLivenessTest >"$tmp/stdout" 2>"$tmp/stderr"; then
  echo 'HOST_WAL_HANDOFF_LIVENESS_FAIL: nonzero Java exit or timeout' >&2
  cat "$tmp/stderr" >&2
  exit 1
fi
printf '%s\n' 'HOST_WAL_HANDOFF_LIVENESS_PASS (not Oreslang guest)' >"$tmp/expected"
if ! cmp -s "$tmp/stdout" "$tmp/expected"; then
  echo 'HOST_WAL_HANDOFF_LIVENESS_FAIL: stdout mismatch' >&2
  cat "$tmp/stdout" "$tmp/stderr" >&2
  exit 1
fi
cat "$tmp/stdout"
if ! timeout 35s "$JAVA_BIN" -cp "$tmp" \
    org.oreslang.db.primitive.WalHandoffSaturationTest >"$tmp/stdout" 2>"$tmp/stderr"; then
  echo 'HOST_WAL_HANDOFF_BOUND_FAIL: nonzero Java exit or timeout' >&2
  cat "$tmp/stderr" >&2
  exit 1
fi
printf '%s\n' 'HOST_WAL_HANDOFF_BOUND_PASS slots=32 (not Oreslang guest)' >"$tmp/expected"
if ! cmp -s "$tmp/stdout" "$tmp/expected"; then
  echo 'HOST_WAL_HANDOFF_BOUND_FAIL: stdout mismatch' >&2
  cat "$tmp/stdout" "$tmp/stderr" >&2
  exit 1
fi
cat "$tmp/stdout"
if ! timeout 35s "$JAVA_BIN" -cp "$tmp" \
    org.oreslang.db.primitive.WalEmergencyQuiescenceTest >"$tmp/stdout" 2>"$tmp/stderr"; then
  echo 'HOST_WAL_EMERGENCY_QUIESCENCE_FAIL: nonzero Java exit or timeout' >&2
  cat "$tmp/stderr" >&2
  exit 1
fi
printf '%s\n' 'HOST_WAL_EMERGENCY_QUIESCENCE_PASS (not Oreslang guest)' >"$tmp/expected"
if ! cmp -s "$tmp/stdout" "$tmp/expected"; then
  echo 'HOST_WAL_EMERGENCY_QUIESCENCE_FAIL: stdout mismatch' >&2
  cat "$tmp/stdout" "$tmp/stderr" >&2
  exit 1
fi
cat "$tmp/stdout"
echo 'HOST_HANDOFF_ONLY; NO GUEST, COMPILER, RUNTIME-GRANT OR DURABILITY CLAIM'
