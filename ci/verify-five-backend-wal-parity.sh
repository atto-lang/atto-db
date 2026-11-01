#!/bin/sh
# Five independently attested compiler/launcher CLI adapters; fail closed.
# Every adapter MUST support '--check <file>' and '<file>' using the unchanged
# Oreslang guest source graph. Adapter wrappers must pin native artifact IDs.
# Preflight all five binaries before giving ANY per-backend execution credit.
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
# Provenance pins are a necessary consistency gate, not independent evidence
# that a binary was built from that source. Native release receipts still apply.
check_source_checkout() {
  root="$1"; commit="$2"; tree="$3"; backend="$4"
  case "$root" in /*) ;; *) echo "BACKEND_SOURCE_ROOT_UNAVAILABLE $backend" >&2; exit 2 ;; esac
  for sha in "$commit" "$tree"; do
    test "${#sha}" -eq 40 || { echo "BACKEND_SOURCE_PIN_UNAVAILABLE $backend" >&2; exit 2; }
    case "$sha" in *[!0123456789abcdef]*|'')
      echo "BACKEND_SOURCE_PIN_INVALID $backend" >&2; exit 2 ;;
    esac
  done
  test -d "$root" || { echo "BACKEND_SOURCE_ROOT_MISSING $backend" >&2; exit 2; }
  physical="$(cd "$root" && pwd -P)"
  test "$(git -C "$root" rev-parse --show-toplevel)" = "$physical" || {
    echo "BACKEND_SOURCE_NOT_REPO_ROOT $backend" >&2; exit 2;
  }
  test -z "$(git -C "$root" status --porcelain)" || {
    echo "BACKEND_SOURCE_DIRTY $backend" >&2; exit 2;
  }
  test "$(git -C "$root" rev-parse HEAD)" = "$commit" || {
    echo "BACKEND_SOURCE_COMMIT_MISMATCH $backend" >&2; exit 2;
  }
  test "$(git -C "$root" rev-parse 'HEAD^{tree}')" = "$tree" || {
    echo "BACKEND_SOURCE_TREE_MISMATCH $backend" >&2; exit 2;
  }
}
seen_digests=''
for phase in preflight execute; do
  for backend in java llvm wasm cranelift dotnet; do
    case "$backend" in
      java) bin="${DB_JAVA_COMPILER_BIN:-}"; digest="${DB_JAVA_COMPILER_SHA256:-}"
            source_root="${DB_JAVA_SOURCE_ROOT:-}"; source_commit="${DB_JAVA_SOURCE_COMMIT:-}"
            source_tree="${DB_JAVA_SOURCE_TREE:-}" ;;
      llvm) bin="${DB_LLVM_COMPILER_BIN:-}"; digest="${DB_LLVM_COMPILER_SHA256:-}"
            source_root="${DB_LLVM_SOURCE_ROOT:-}"; source_commit="${DB_LLVM_SOURCE_COMMIT:-}"
            source_tree="${DB_LLVM_SOURCE_TREE:-}" ;;
      wasm) bin="${DB_WASM_COMPILER_BIN:-}"; digest="${DB_WASM_COMPILER_SHA256:-}"
            source_root="${DB_WASM_SOURCE_ROOT:-}"; source_commit="${DB_WASM_SOURCE_COMMIT:-}"
            source_tree="${DB_WASM_SOURCE_TREE:-}" ;;
      cranelift) bin="${DB_CRANELIFT_COMPILER_BIN:-}"; digest="${DB_CRANELIFT_COMPILER_SHA256:-}"
            source_root="${DB_CRANELIFT_SOURCE_ROOT:-}"; source_commit="${DB_CRANELIFT_SOURCE_COMMIT:-}"
            source_tree="${DB_CRANELIFT_SOURCE_TREE:-}" ;;
      dotnet) bin="${DB_DOTNET_COMPILER_BIN:-}"; digest="${DB_DOTNET_COMPILER_SHA256:-}"
            source_root="${DB_DOTNET_SOURCE_ROOT:-}"; source_commit="${DB_DOTNET_SOURCE_COMMIT:-}"
            source_tree="${DB_DOTNET_SOURCE_TREE:-}" ;;
    esac
    case "$bin" in /*) ;; *) echo "BACKEND_NOT_CONFIGURED $backend" >&2; exit 2 ;; esac
    case "$digest" in
      *[!0123456789abcdef]*|'') echo "BINARY_PIN_UNAVAILABLE $backend" >&2; exit 2 ;;
    esac
    test "${#digest}" -eq 64 || exit 2
    test -x "$bin" || { echo "BINARY_NOT_EXECUTABLE $backend" >&2; exit 2; }
    printf '%s  %s\n' "$digest" "$bin" | sha256sum -c - >/dev/null || {
      echo "BINARY_DIGEST_MISMATCH $backend" >&2; exit 2;
    }
    check_source_checkout "$source_root" "$source_commit" "$source_tree" "$backend"
    if [ "$phase" = preflight ]; then
      case " $seen_digests " in
        *" $digest "*) echo "BACKEND_BINARY_REUSED $backend" >&2; exit 2 ;;
      esac
      seen_digests="$seen_digests $digest"
    fi
    if [ "$phase" = execute ]; then
      echo "BACKEND_ATTEMPT=$backend"
      ORESLANG_BIN="$bin" ORESLANG_COMPILER_SHA256="$digest" \
        sh ci/verify-native-wal.sh
      echo "BACKEND_GUEST_CODEC_PASS=$backend cases=15"
    fi
  done
  if [ "$phase" = preflight ]; then echo 'FIVE_BACKEND_PREFLIGHT_PASS; NO GUEST EXECUTION YET'; fi
done
echo 'FIVE_BACKEND_ODB1_EXECUTION_CANDIDATE_PASS cases=75; NO AUTHENTICATION OR DURABILITY CLAIM'
