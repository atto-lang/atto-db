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
grep -q '^FIVE_BACKEND_ODB1_GUEST_PARITY_PASS cases=45;' "$tmp/out"
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
printf 'dirty\n' > "$tmp/repo/untracked"
assert_preflight_failure env
rm "$tmp/repo/untracked"
echo 'HOST_PARITY_COORDINATOR_CONTRACT_PASS cases=5; MOCK EXECUTABLES ONLY; NO NATIVE GUEST EXECUTIONS'
