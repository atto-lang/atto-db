#!/bin/sh
# Strict clean, independently pinned HOST-only post-sync crash/replay oracle.
# Never interpret this as Oreslang guest execution or power-loss proof.
set -eu
export LC_ALL=C
: "${DB_EXPECTED_COMMIT:?independently provided exact HEAD required}"
: "${DB_EXPECTED_TREE:?independently provided exact tree required}"
: "${JAVA_BIN:?absolute independently attested Java 21 binary required}"
: "${JAVAC_BIN:?absolute independently attested Javac 21 binary required}"
: "${JAVA_SHA256:?independent java binary SHA256 required}"
: "${JAVAC_SHA256:?independent javac binary SHA256 required}"
for d in "$DB_EXPECTED_COMMIT" "$DB_EXPECTED_TREE"; do
  case "$d" in ''|*[!0123456789abcdef]*) exit 2;; esac
  test "${#d}" -eq 40 || exit 2
done
for d in "$JAVA_SHA256" "$JAVAC_SHA256"; do
  case "$d" in ''|*[!0123456789abcdef]*) exit 2;; esac
  test "${#d}" -eq 64 || exit 2
done
case "$JAVA_BIN:$JAVAC_BIN" in /*:/*) ;; *) exit 2;; esac
printf '%s  %s\n' "$JAVA_SHA256" "$JAVA_BIN" | sha256sum -c - >/dev/null
printf '%s  %s\n' "$JAVAC_SHA256" "$JAVAC_BIN" | sha256sum -c - >/dev/null
test "$(pwd -P)" = "$(git rev-parse --show-toplevel)"
test -z "$(git status --porcelain)"
test "$(git rev-parse HEAD)" = "$DB_EXPECTED_COMMIT"
test "$(git rev-parse 'HEAD^{tree}')" = "$DB_EXPECTED_TREE"
test "$(git hash-object runtime/java/src/org/oreslang/db/primitive/ReadOnlyWalReplay.java)" = a0db92f5c916590d79c9e0e7d087d29e9e081f5a
test "$(git hash-object runtime/java/test/org/oreslang/db/primitive/WalPostSyncCrashReplayTest.java)" = 193e949ad54be5911c324f1c79a0d1252b309984
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
"$JAVAC_BIN" -Xlint:all -Werror -d "$tmp" \
  runtime/java/src/org/oreslang/db/primitive/ReadOnlyWalReplay.java \
  runtime/java/test/org/oreslang/db/primitive/WalPostSyncCrashReplayTest.java
timeout 55s "$JAVA_BIN" -cp "$tmp" \
  org.oreslang.db.primitive.WalPostSyncCrashReplayTest > "$tmp/stdout" 2>"$tmp/stderr" || {
  cat "$tmp/stderr" >&2; exit 1;
}
printf '%s\n' 'HOST_WAL_POSTSYNC_CRASH_REPLAY_PASS checks=18 (JVM SIGKILL and read-only candidate replay; NOT guest, DB commit, or power loss)' > "$tmp/expected"
cmp -s "$tmp/stdout" "$tmp/expected" || { cat "$tmp/stdout" "$tmp/stderr" >&2; exit 1; }
printf 'SOURCE_COMMIT=%s\nSOURCE_TREE=%s\n' "$DB_EXPECTED_COMMIT" "$DB_EXPECTED_TREE"
cat "$tmp/stdout"
echo 'HOST_PROCESS_CRASH_CANDIDATE_ONLY; NO ORESLANG_GUEST, DB_ACK OR POWER_LOSS_PROOF'
