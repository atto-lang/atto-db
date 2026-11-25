#!/bin/sh
# Stage4: HOST Java grant seam. Inherits exact checkout/tree and Java hashes.
# Deliberately NOT a guest/compiler, runtime interop, filesystem authority,
# cross-backend parity, or OS power-loss durability receipt.
set -eu
export LC_ALL=C
sh ci/verify-java-storage-primitive.sh
source=runtime/java/src/org/oreslang/db/primitive/WalFilePrimitive.java
grant=runtime/java/src/org/oreslang/db/primitive/WalRuntimeGrant.java
grant_test=runtime/java/test/org/oreslang/db/primitive/WalRuntimeGrantTest.java
sha256sum "$grant" "$grant_test" src/wal/wal_actor.ores
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
"$JAVAC_BIN" -Xlint:all -Werror -d "$tmp" "$source" "$grant" "$grant_test"
if ! timeout 30s "$JAVA_BIN" -cp "$tmp" \
  org.oreslang.db.primitive.WalRuntimeGrantTest >"$tmp/actual" 2>"$tmp/stderr"; then
  echo 'HOST_WAL_RUNTIME_GRANT_FAIL: nonzero exit or timeout' >&2
  cat "$tmp/stderr" >&2
  exit 1
fi
printf '%s\n' 'HOST_WAL_RUNTIME_GRANT_PASS checks=16 (not Oreslang guest)' >"$tmp/expected"
if ! cmp -s "$tmp/actual" "$tmp/expected"; then
  echo 'HOST_WAL_RUNTIME_GRANT_FAIL: stdout mismatch' >&2
  cat "$tmp/stderr" "$tmp/actual" >&2
  exit 1
fi
cat "$tmp/actual"
echo 'HOST_GRANT_SEAM_ONLY; ORESLANG_ACTOR_NOT_EXECUTED; NO_DB_DURABILITY_CLAIM'
