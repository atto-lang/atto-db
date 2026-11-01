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
cat > "$tmp/repo/ci/verify-native-wal.sh" <<'MOCK_GATE'
#!/bin/sh
# Test-only fake verifier. Never count its output as compiled Oreslang.
set -eu
case "${ORESLANG_BACKEND:-}" in
  java|llvm|wasm|cranelift|dotnet) ;;
  *) echo 'MOCK_BACKEND_NOT_PASSED' >&2; exit 45 ;;
esac
if [ "${MOCK_FAIL_BACKEND:-}" = "$ORESLANG_BACKEND" ]; then
  echo "MOCK_GUEST_FAILED_BACKEND=$ORESLANG_BACKEND" >&2
  exit 17
fi
if [ "${MOCK_MUTATE_DB_BACKEND:-}" = "$ORESLANG_BACKEND" ]; then
  printf 'unexpected guest output\n' > "${MOCK_DB_ROOT:?}/guest-mutated-repo"
fi
if [ "${MOCK_MUTATE_SOURCE_BACKEND:-}" = "$ORESLANG_BACKEND" ]; then
  printf 'unexpected compiler output\n' > "${MOCK_SOURCE_ROOT:?}/guest-mutated-backend"
fi
if [ "${MOCK_MUTATE_BIN_BACKEND:-}" = "$ORESLANG_BACKEND" ]; then
  printf '# unexpected binary rewrite\n' >> "${MOCK_COMPILER_BIN:?}"
fi
echo MOCK_WAL_GATE_ONLY
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
grep -q '^FIVE_BACKEND_ODB1_EXECUTION_CANDIDATE_PASS cases=75;' "$tmp/out"
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
# A failed actual guest must reject the entire matrix, including when the
# final backend fails after four other mocked backends emitted their markers.
assert_execute_failure() {
  if (cd "$tmp/repo" && "$@" sh ci/verify-five-backend-wal-parity.sh) > "$tmp/out" 2> "$tmp/err"; then
    echo 'FAILED: guest execution or post-execution mutation was credited' >&2
    exit 1
  fi
  if grep -q '^FIVE_BACKEND_ODB1_EXECUTION_CANDIDATE_PASS' "$tmp/out"; then
    echo 'FAILED: global success marker present despite guest failure' >&2
    exit 1
  fi
}
for backend in java llvm wasm cranelift dotnet; do
  assert_execute_failure env MOCK_FAIL_BACKEND="$backend"
done
# The most important time-of-check/use case: the final guest exits 0 but
# dirties the DB source, backend source or compiler binary after preflight.
assert_execute_failure env MOCK_MUTATE_DB_BACKEND=dotnet MOCK_DB_ROOT="$tmp/repo"
rm "$tmp/repo/guest-mutated-repo"
assert_execute_failure env MOCK_MUTATE_SOURCE_BACKEND=dotnet MOCK_SOURCE_ROOT="$tmp/sources/dotnet"
rm "$tmp/sources/dotnet/guest-mutated-backend"
assert_execute_failure env MOCK_MUTATE_BIN_BACKEND=dotnet MOCK_COMPILER_BIN="$tmp/compilers/dotnet"
printf '#!/bin/sh\necho MOCK_dotnet\n' > "$tmp/compilers/dotnet"
chmod 700 "$tmp/compilers/dotnet"
test "$(sha256sum "$tmp/compilers/dotnet" | cut -d ' ' -f1)" = "$DB_DOTNET_COMPILER_SHA256"
echo 'HOST_PARITY_COORDINATOR_CONTRACT_PASS cases=22; MOCK EXECUTABLES ONLY; NO NATIVE GUEST EXECUTIONS' 
