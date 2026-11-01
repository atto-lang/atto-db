#!/bin/sh
# Source-free mock test for malformed guest challenge. No Oreslang execution.
set -eu
export LC_ALL=C
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
mkdir -p "$tmp/ci" "$tmp/tests/guest/positive" "$tmp/bin"
cp "$root/ci/verify-native-wal-negative-challenge.sh" "$tmp/ci/"
printf '%s\n' 'pub routine main(): void { stdio.stdout.write("ok"); }' > "$tmp/tests/guest/positive/frame-roundtrip.ores"
printf '%s\n' '#!/bin/sh' 'exit 0' > "$tmp/bin/accept-all"
cat > "$tmp/bin/mock-grammar" <<'MOCK'
#!/bin/sh
set -eu
case "${MOCK_MODE:-reject-both}" in
  reject-both) exit 1 ;;
  timeout-like) exit 124 ;;
  fatal-signal) exit 139 ;;
  accept-check) if [ "${1:-}" = --check ]; then exit 0; else exit 1; fi ;;
  accept-runtime) if [ "${1:-}" = --check ]; then exit 1; else exit 0; fi ;;
  reject-invalid)
    case "${1:-}" in --check) shift;; esac
    if grep -q 'ODB1_SYNTACTICALLY_IMPOSSIBLE' "$1"; then exit 1; fi
    exit 0 ;;
  *) exit 99 ;;
esac
MOCK
chmod 700 "$tmp/bin/accept-all" "$tmp/bin/mock-grammar"
run_challenge() {
  exe="$1"; digest=$(sha256sum "$exe" | cut -d ' ' -f1)
  (cd "$tmp" && ORESLANG_BIN="$exe" ORESLANG_COMPILER_SHA256="$digest" \
    sh ci/verify-native-wal-negative-challenge.sh)
}
reject_challenge() {
  if run_challenge "$1" > "$tmp/out" 2> "$tmp/err"; then
    echo 'HOST_NEGATIVE_SOURCE_TEST_FAILED: accepted invalid compiler' >&2
    exit 1
  fi
}
reject_challenge "$tmp/bin/accept-all"
MOCK_MODE=accept-check; export MOCK_MODE
reject_challenge "$tmp/bin/mock-grammar"
MOCK_MODE=accept-runtime; export MOCK_MODE
reject_challenge "$tmp/bin/mock-grammar"
MOCK_MODE=timeout-like; export MOCK_MODE
reject_challenge "$tmp/bin/mock-grammar"
MOCK_MODE=fatal-signal; export MOCK_MODE
reject_challenge "$tmp/bin/mock-grammar"
MOCK_MODE=reject-both; export MOCK_MODE
run_challenge "$tmp/bin/mock-grammar" > "$tmp/out"
grep -q '^NATIVE_NEGATIVE_SOURCE_CHALLENGE_PASS' "$tmp/out"
MOCK_MODE=reject-invalid; export MOCK_MODE
run_challenge "$tmp/bin/mock-grammar" > "$tmp/out"
grep -q '^NATIVE_NEGATIVE_SOURCE_CHALLENGE_PASS' "$tmp/out"
# A changed digest must reject, even if the mock otherwise rejects syntax.
if (cd "$tmp" && ORESLANG_BIN="$tmp/bin/mock-grammar" \
    ORESLANG_COMPILER_SHA256=0000000000000000000000000000000000000000000000000000000000000000 \
    sh ci/verify-native-wal-negative-challenge.sh) > "$tmp/out" 2> "$tmp/err"; then
  echo 'HOST_NEGATIVE_SOURCE_TEST_FAILED: digest mismatch accepted' >&2
  exit 1
fi
# The temporary malformed guest must not leak into the source checkout.
if find "$tmp/tests/guest/positive" -type f -name 'odb1-invalid-*' | grep -q .; then
  echo 'HOST_NEGATIVE_SOURCE_TEST_FAILED: malformed temp source leaked' >&2
  exit 1
fi
printf '%s\n' 'HOST_NATIVE_NEGATIVE_CHALLENGE_CONTRACT_PASS cases=9; MOCK COMPILERS ONLY; NO GUEST EXECUTION'
