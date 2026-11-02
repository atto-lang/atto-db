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
printf '%s\n' '#!/bin/sh' 'set -eu' 'echo MOCK_WAL_GATE_ONLY' > "$tmp/repo/ci/verify-native-wal.sh"
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
# An independently labeled compiler must not borrow another backend's
# checkout, even if the caller supplies that checkout's correct commit/tree.
assert_preflight_failure env DB_LLVM_SOURCE_ROOT="$DB_JAVA_SOURCE_ROOT" \
  DB_LLVM_SOURCE_COMMIT="$DB_JAVA_SOURCE_COMMIT" \
  DB_LLVM_SOURCE_TREE="$DB_JAVA_SOURCE_TREE"
grep -Fq 'BACKEND_SOURCE_ROOT_REUSED llvm' "$tmp/err"
# The DB fixture repository itself is never a backend compiler checkout.
assert_preflight_failure env DB_JAVA_SOURCE_ROOT="$tmp/repo" \
  DB_JAVA_SOURCE_COMMIT="$DB_EXPECTED_COMMIT" \
  DB_JAVA_SOURCE_TREE="$DB_EXPECTED_TREE"
grep -Fq 'BACKEND_SOURCE_IS_DB_REPO java' "$tmp/err"
# A path alias must not allow a source checkout to be silently recycled.
ln -s "$DB_JAVA_SOURCE_ROOT" "$tmp/sources/java-alias"
assert_preflight_failure env DB_WASM_SOURCE_ROOT="$tmp/sources/java-alias" \
  DB_WASM_SOURCE_COMMIT="$DB_JAVA_SOURCE_COMMIT" \
  DB_WASM_SOURCE_TREE="$DB_JAVA_SOURCE_TREE"
echo 'HOST_PARITY_COORDINATOR_CONTRACT_PASS cases=17; MOCK EXECUTABLES ONLY; NO NATIVE GUEST EXECUTIONS'
