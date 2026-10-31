#!/bin/sh
# Strict exact-head standalone Java 21 HOST-PRIMITIVE gate.
# This is NOT Oreslang guest, actor, filesystem-capability integration,
# storage recovery, checkpoint, or power-loss durability evidence.
set -eu
export LC_ALL=C
: "${DB_EXPECTED_COMMIT:?independently pinned source HEAD required}"
: "${DB_EXPECTED_TREE:?independently pinned source tree SHA required}"
: "${JAVAC_BIN:?absolute trusted javac binary required}"
: "${JAVA_BIN:?absolute trusted java binary required}"
: "${JAVAC_SHA256:?independently pinned javac executable digest required}"
: "${JAVA_SHA256:?independently pinned java executable digest required}"
case "$JAVAC_BIN:$JAVA_BIN" in /*:/*) ;; *) echo 'absolute Java executables required' >&2; exit 2;; esac
for digest in "$JAVAC_SHA256" "$JAVA_SHA256"; do
  case "$digest" in *[!0123456789abcdef]*|'') echo 'invalid executable digest' >&2; exit 2;; esac
  test "${#digest}" -eq 64
done
printf '%s  %s\n' "$JAVAC_SHA256" "$JAVAC_BIN" | sha256sum -c - >/dev/null
printf '%s  %s\n' "$JAVA_SHA256" "$JAVA_BIN" | sha256sum -c - >/dev/null
command -v git >/dev/null
command -v timeout >/dev/null
root="$(git rev-parse --show-toplevel)"
test "$(pwd -P)" = "$root"
test -z "$(git status --porcelain)"
commit="$(git rev-parse HEAD)"
tree="$(git rev-parse 'HEAD^{tree}')"
test "$DB_EXPECTED_COMMIT" = "$commit"
test "$DB_EXPECTED_TREE" = "$tree"
printf 'SOURCE_COMMIT=%s\nSOURCE_TREE=%s\nJAVAC_SHA256=%s\nJAVA_SHA256=%s\n' \
  "$commit" "$tree" "$JAVAC_SHA256" "$JAVA_SHA256"
source=runtime/java/src/org/oreslang/db/primitive/WalFilePrimitive.java
test=runtime/java/test/org/oreslang/db/primitive/WalFilePrimitiveTest.java
sha256sum "$source" "$test"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
"$JAVAC_BIN" -Xlint:all -Werror -d "$tmp" "$source" "$test"
if ! timeout 40s "$JAVA_BIN" -cp "$tmp" \
    org.oreslang.db.primitive.WalFilePrimitiveTest >"$tmp/stdout" 2>"$tmp/stderr"; then
  echo 'HOST_JAVA_PRIMITIVE_FAIL: Java exited nonzero or timed out' >&2
  cat "$tmp/stderr" >&2
  exit 1
fi
printf '%s\n' 'HOST_JAVA_PRIMITIVE_PASS assertions=75 (not Oreslang guest; process-kill post-sync only)' >"$tmp/expected"
if ! cmp -s "$tmp/stdout" "$tmp/expected"; then
  echo 'HOST_JAVA_PRIMITIVE_FAIL: exact stdout mismatch' >&2
  cat "$tmp/stdout" >&2
  cat "$tmp/stderr" >&2
  exit 1
fi
cat "$tmp/stdout"
echo 'HOST_ONLY_VALIDATED; GUEST_EXECUTION_NOT_EVIDENCED; POWER_LOSS_NOT_EVIDENCED'
