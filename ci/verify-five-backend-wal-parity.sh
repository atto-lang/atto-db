#!/bin/sh
# Five independently attested compiler/launcher CLI adapters; fail closed.
# Every adapter MUST support '--check <file>' and '<file>' using the unchanged
# Oreslang guest source graph. Adapter wrappers must pin native artifact IDs.
set -eu
export LC_ALL=C
: "${DB_EXPECTED_COMMIT:?source commit must be independently pinned}"
: "${DB_EXPECTED_TREE:?source Git tree must be independently pinned}"
test "${#DB_EXPECTED_COMMIT}" -eq 40
test "${#DB_EXPECTED_TREE}" -eq 40
case "${DB_EXPECTED_COMMIT}${DB_EXPECTED_TREE}" in
  *[!0123456789abcdef]*) echo 'invalid exact source pin' >&2; exit 2 ;;
esac
test -z "$(git status --porcelain)" || { echo 'dirty repository' >&2; exit 2; }
test "$(git rev-parse HEAD)" = "$DB_EXPECTED_COMMIT"
test "$(git rev-parse 'HEAD^{tree}')" = "$DB_EXPECTED_TREE"
for backend in java llvm wasm cranelift dotnet; do
  case "$backend" in
    java) bin="${DB_JAVA_COMPILER_BIN:-}"; digest="${DB_JAVA_COMPILER_SHA256:-}" ;;
    llvm) bin="${DB_LLVM_COMPILER_BIN:-}"; digest="${DB_LLVM_COMPILER_SHA256:-}" ;;
    wasm) bin="${DB_WASM_COMPILER_BIN:-}"; digest="${DB_WASM_COMPILER_SHA256:-}" ;;
    cranelift) bin="${DB_CRANELIFT_COMPILER_BIN:-}"; digest="${DB_CRANELIFT_COMPILER_SHA256:-}" ;;
    dotnet) bin="${DB_DOTNET_COMPILER_BIN:-}"; digest="${DB_DOTNET_COMPILER_SHA256:-}" ;;
  esac
  case "$bin" in /*) ;; *) echo "BACKEND_NOT_CONFIGURED $backend" >&2; exit 2 ;; esac
  case "$digest" in
    *[!0123456789abcdef]*|'') echo "BINARY_PIN_UNAVAILABLE $backend" >&2; exit 2 ;;
  esac
  test "${#digest}" -eq 64 || exit 2
  test -x "$bin" || exit 2
  printf '%s  %s\n' "$digest" "$bin" | sha256sum -c - >/dev/null
  echo "BACKEND_ATTEMPT=$backend"
  ORESLANG_BIN="$bin" ORESLANG_COMPILER_SHA256="$digest" \
    sh ci/verify-native-wal.sh
  echo "BACKEND_GUEST_CODEC_PASS=$backend cases=7"
done
echo 'FIVE_BACKEND_ODB1_GUEST_PARITY_PASS cases=35; NO DURABILITY CLAIM'
