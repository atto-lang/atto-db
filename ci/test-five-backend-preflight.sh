#!/bin/sh
# Host-only contract test: uses FAKE compiler executables and a fake WAL gate.
# Verifies the parity coordinator's fail-closed preflight; NO .ores guest runs.
set -eu
export LC_ALL=C
command -v git >/dev/null
command -v sha256sum >/dev/null
root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
mkdir -p "$tmp/repo/ci" "$tmp/compilers"
cp "$root/ci/verify-five-backend-wal-parity.sh" "$tmp/repo/ci/"
cp "$root/ci/assert-native-wal-receipts.sh" "$tmp/repo/ci/"
# Fake receipts deliberately test only the host contract, never .ores guests.
sed -n 's/^run_case \(tests\/guest\/[^ ]*\) .*/\1/p' "$root/ci/verify-native-wal.sh" > "$tmp/repo/ci/guest-fixtures.list"
test "$(wc -l < "$tmp/repo/ci/guest-fixtures.list")" -eq 16
# Fixture digests are validated against actual bytes, even in host-only mocks.
while IFS= read -r fixture; do
  test -f "$root/$fixture" || exit 1
  mkdir -p "$tmp/repo/$(dirname "$fixture")"
  cp "$root/$fixture" "$tmp/repo/$fixture"
done < "$tmp/repo/ci/guest-fixtures.list"
cat > "$tmp/repo/ci/verify-native-wal.sh" <<'MOCK_GATE'
#!/bin/sh
set -eu
printf 'SOURCE_COMMIT=%s\nSOURCE_TREE=%s\nCOMPILER_BINARY_SHA256=%s\n' \
  "$DB_EXPECTED_COMMIT" "$DB_EXPECTED_TREE" "$ORESLANG_COMPILER_SHA256"
while IFS= read -r fixture; do
  actual_hash="$(sha256sum "$fixture" | cut -d ' ' -f1)"
  printf 'FIXTURE_SHA256=%s  %s\n' "$actual_hash" "$fixture"
  printf 'GUEST_EXECUTED_PASS %s\n' "$fixture"
done < ci/guest-fixtures.list
echo 'GUEST_WAL_CODEC_ONLY_PASS cases=16; NO AUTHENTICATION, NO DURABILITY CLAIM'
MOCK_GATE
for backend in java llvm wasm cranelift dotnet; do
  printf '#!/bin/sh\necho MOCK_%s\n' "$backend" > "$tmp/compilers/$backend"
  chmod 700 "$tmp/compilers/$backend"
  mkdir -p "$tmp/sources/$backend"
  (
    cd "$tmp/sources/$backend"
    git init -q
    printf 'test-only-source=%s\n' "$backend" > provenance.txt
    git add .
    git -c user.name=gate-test -c user.email=gate-test@invalid.example commit -qm source
  )
  upper="$(printf '%s' "$backend" | tr '[:lower:]' '[:upper:]')"
  source_root="$tmp/sources/$backend"
  source_commit="$(git -C "$source_root" rev-parse HEAD)"
  source_tree="$(git -C "$source_root" rev-parse 'HEAD^{tree}')"
  export "DB_${upper}_SOURCE_ROOT=$source_root"
  export "DB_${upper}_SOURCE_COMMIT=$source_commit"
  export "DB_${upper}_SOURCE_TREE=$source_tree"
done
(
  cd "$tmp/repo"
  git init -q
  git -c user.name=gate-test -c user.email=gate-test@invalid.example add .
  git -c user.name=gate-test -c user.email=gate-test@invalid.example commit -qm 'offline gate fixture'
)
export DB_EXPECTED_COMMIT="$(git -C "$tmp/repo" rev-parse HEAD)"
export DB_EXPECTED_TREE="$(git -C "$tmp/repo" rev-parse 'HEAD^{tree}')"
export DB_JAVA_COMPILER_BIN="$tmp/compilers/java"
export DB_LLVM_COMPILER_BIN="$tmp/compilers/llvm"
export DB_WASM_COMPILER_BIN="$tmp/compilers/wasm"
export DB_CRANELIFT_COMPILER_BIN="$tmp/compilers/cranelift"
export DB_DOTNET_COMPILER_BIN="$tmp/compilers/dotnet"
export DB_JAVA_COMPILER_SHA256="$(sha256sum "$DB_JAVA_COMPILER_BIN" | cut -d ' ' -f1)"
export DB_LLVM_COMPILER_SHA256="$(sha256sum "$DB_LLVM_COMPILER_BIN" | cut -d ' ' -f1)"
export DB_WASM_COMPILER_SHA256="$(sha256sum "$DB_WASM_COMPILER_BIN" | cut -d ' ' -f1)"
export DB_CRANELIFT_COMPILER_SHA256="$(sha256sum "$DB_CRANELIFT_COMPILER_BIN" | cut -d ' ' -f1)"
export DB_DOTNET_COMPILER_SHA256="$(sha256sum "$DB_DOTNET_COMPILER_BIN" | cut -d ' ' -f1)"
(
  cd "$tmp/repo"
  sh ci/verify-five-backend-wal-parity.sh
) > "$tmp/out" 2> "$tmp/err"
grep -q '^FIVE_BACKEND_ODB1_EXECUTION_CANDIDATE_PASS cases=80;' "$tmp/out"
test "$(grep -c '^BACKEND_ATTEMPT=' "$tmp/out")" -eq 5
# Invalid configurations must fail *before* any per-backend guest credit.
assert_preflight_failure() {
  if (cd "$tmp/repo" && "$@" sh ci/verify-five-backend-wal-parity.sh) > "$tmp/out" 2> "$tmp/err"; then
    echo 'FAILED: malformed configuration accepted by host-only parity gate' >&2
    exit 1
  fi
  if grep -q '^BACKEND_ATTEMPT=' "$tmp/out"; then
    echo 'FAILED: credited a backend before whole-matrix preflight' >&2
    exit 1
  fi
}
assert_preflight_failure env DB_DOTNET_COMPILER_BIN=
assert_preflight_failure env DB_WASM_COMPILER_SHA256=0000000000000000000000000000000000000000000000000000000000000000
assert_preflight_failure env DB_EXPECTED_COMMIT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
assert_preflight_failure env DB_LLVM_COMPILER_BIN="$DB_JAVA_COMPILER_BIN" DB_LLVM_COMPILER_SHA256="$DB_JAVA_COMPILER_SHA256"
printf 'dirty\n' > "$tmp/repo/untracked"
assert_preflight_failure env
rm "$tmp/repo/untracked"
assert_preflight_failure env DB_WASM_COMPILER_BIN=relative-path-not-allowed
assert_preflight_failure env DB_JAVA_COMPILER_SHA256=incorrect-length-digest
assert_preflight_failure env DB_CRANELIFT_COMPILER_BIN=/absent-cranelift-compiler
assert_preflight_failure env DB_EXPECTED_TREE=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
assert_preflight_failure env DB_LLVM_SOURCE_ROOT=relative-source-root
assert_preflight_failure env DB_DOTNET_SOURCE_COMMIT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
printf 'dirty\n' > "$tmp/sources/wasm/untracked"
assert_preflight_failure env
rm "$tmp/sources/wasm/untracked"
assert_preflight_failure env DB_CRANELIFT_SOURCE_TREE=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
# Different binary wrappers may NOT reuse an identical backend source root.
assert_preflight_failure env \
  DB_LLVM_SOURCE_ROOT="$DB_JAVA_SOURCE_ROOT" \
  DB_LLVM_SOURCE_COMMIT="$DB_JAVA_SOURCE_COMMIT" \
  DB_LLVM_SOURCE_TREE="$DB_JAVA_SOURCE_TREE"
# Reject an identical physical backend checkout, even with different compiler binaries.
assert_preflight_failure env \
  DB_LLVM_SOURCE_ROOT="$DB_JAVA_SOURCE_ROOT" \
  DB_LLVM_SOURCE_COMMIT="$DB_JAVA_SOURCE_COMMIT" \
  DB_LLVM_SOURCE_TREE="$DB_JAVA_SOURCE_TREE"
grep -Fq 'BACKEND_SOURCE_ROOT_REUSED llvm' "$tmp/err"
# Backend source cannot be the ODB1 fixture repository itself.
assert_preflight_failure env DB_JAVA_SOURCE_ROOT="$tmp/repo" \
  DB_JAVA_SOURCE_COMMIT="$DB_EXPECTED_COMMIT" \
  DB_JAVA_SOURCE_TREE="$DB_EXPECTED_TREE"
grep -Fq 'BACKEND_SOURCE_IS_DB_REPO java' "$tmp/err"
ln -s "$DB_JAVA_SOURCE_ROOT" "$tmp/sources/java-alias"
assert_preflight_failure env DB_WASM_SOURCE_ROOT="$tmp/sources/java-alias" \
  DB_WASM_SOURCE_COMMIT="$DB_JAVA_SOURCE_COMMIT" \
  DB_WASM_SOURCE_TREE="$DB_JAVA_SOURCE_TREE"
# Child may exit 0 with a fake terminal success; no guest credit is allowed.
assert_no_guest_credit() {
  if (cd "$tmp/repo" && sh ci/verify-five-backend-wal-parity.sh) > "$tmp/out" 2> "$tmp/err"; then
    echo 'FAILED: incomplete guest receipt accepted as five-backend parity' >&2
    exit 1
  fi
  if grep -q '^BACKEND_GUEST_CODEC_PASS=' "$tmp/out"; then
    echo 'FAILED: backend credited without complete guest receipts' >&2
    exit 1
  fi
}
cp "$tmp/repo/ci/verify-native-wal.sh" "$tmp/valid-mock"
printf '%s\n' '#!/bin/sh' 'echo "GUEST_WAL_CODEC_ONLY_PASS cases=16; NO AUTHENTICATION, NO DURABILITY CLAIM"' \
  > "$tmp/repo/ci/verify-native-wal.sh"
# Re-commit test-only fixture so the exact root/tree preflight succeeds.
(cd "$tmp/repo" && git add . && git -c user.name=gate-test -c user.email=gate-test@invalid.example commit -qm 'mock terminal-only output')
export DB_EXPECTED_COMMIT="$(git -C "$tmp/repo" rev-parse HEAD)"
export DB_EXPECTED_TREE="$(git -C "$tmp/repo" rev-parse 'HEAD^{tree}')"
assert_no_guest_credit
# Child emits valid hashes for its own tampered bytes. The checkout must be
# reverified after that child exits, before reporting any guest credit.
{
  printf '#!/bin/sh\n'
  printf 'printf "malicious\\n" >> tests/guest/positive/frame-roundtrip.ores\n'
  tail -n +2 "$tmp/valid-mock"
} > "$tmp/repo/ci/verify-native-wal.sh"
(cd "$tmp/repo" && git add . && git -c user.name=gate-test -c user.email=gate-test@invalid.example commit -qm 'mutating mock compiler')
export DB_EXPECTED_COMMIT="$(git -C "$tmp/repo" rev-parse HEAD)"
export DB_EXPECTED_TREE="$(git -C "$tmp/repo" rev-parse 'HEAD^{tree}')"
assert_no_guest_credit
grep -q 'ODB1_SOURCE_CHANGED_DURING_GUEST_EXECUTION java' "$tmp/err"
# Last backend can corrupt *its own* source AFTER writing plausible receipts.
# Earlier four backend receipts may be valid; final 80-case claim MUST fail.
{
  printf '#!/bin/sh\n'
  printf 'if [ "${ORESLANG_BACKEND:-}" = dotnet ]; then printf "changed\\n" >> "$DB_DOTNET_SOURCE_ROOT/provenance.txt"; fi\n'
  tail -n +2 "$tmp/valid-mock"
} > "$tmp/repo/ci/verify-native-wal.sh"
(cd "$tmp/repo" && git add . && git -c user.name=gate-test -c user.email=gate-test@invalid.example commit -qm 'late source mutation')
export DB_EXPECTED_COMMIT="$(git -C "$tmp/repo" rev-parse HEAD)"
export DB_EXPECTED_TREE="$(git -C "$tmp/repo" rev-parse 'HEAD^{tree}')"
if (cd "$tmp/repo" && sh ci/verify-five-backend-wal-parity.sh) > "$tmp/out" 2> "$tmp/err"; then
  echo 'FAILED: post-final-backend source mutation accepted' >&2; exit 1
fi
grep -q 'BACKEND_SOURCE_DIRTY dotnet' "$tmp/err"
if grep -q '^FIVE_BACKEND_ODB1_EXECUTION_CANDIDATE_PASS' "$tmp/out"; then
  echo 'FAILED: credited 80 cases despite dirty dotnet source' >&2; exit 1
fi
git -C "$tmp/sources/dotnet" checkout -- provenance.txt
# A compiler executable changed during the last guest run is also rejected.
{
  printf '#!/bin/sh\n'
  printf 'if [ "${ORESLANG_BACKEND:-}" = dotnet ]; then printf "\\n# unexpected mutation\\n" >> "$DB_DOTNET_COMPILER_BIN"; fi\n'
  tail -n +2 "$tmp/valid-mock"
} > "$tmp/repo/ci/verify-native-wal.sh"
(cd "$tmp/repo" && git add . && git -c user.name=gate-test -c user.email=gate-test@invalid.example commit -qm 'late compiler mutation')
export DB_EXPECTED_COMMIT="$(git -C "$tmp/repo" rev-parse HEAD)"
export DB_EXPECTED_TREE="$(git -C "$tmp/repo" rev-parse 'HEAD^{tree}')"
if (cd "$tmp/repo" && sh ci/verify-five-backend-wal-parity.sh) > "$tmp/out" 2> "$tmp/err"; then
  echo 'FAILED: post-final-backend compiler mutation accepted' >&2; exit 1
fi
grep -q 'BACKEND_COMPILER_CHANGED_DURING_EXECUTION dotnet' "$tmp/err"
if grep -q '^FIVE_BACKEND_ODB1_EXECUTION_CANDIDATE_PASS' "$tmp/out"; then
  echo 'FAILED: credited 80 cases despite changed dotnet compiler' >&2; exit 1
fi
echo 'HOST_PARITY_COORDINATOR_CONTRACT_PASS; 16 fixtures, adversarial mocks only; NO NATIVE GUEST EXECUTIONS'
