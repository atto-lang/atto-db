#!/bin/sh
# Source-free mock test for malformed guest challenge. No Oreslang execution.
set -eu
export LC_ALL=C
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
mkdir -p "$tmp/ci" "$tmp/tests/guest/positive" "$tmp/bin"
cp "$root/ci/verify-native-wal-negative-challenge.sh" "$tmp/ci/"
printf '%s\n' 'pub routine main(): void { stdio.stdout.write("db:wal-roundtrip-ok"); }' > "$tmp/tests/guest/positive/frame-roundtrip.ores"
printf '%s\n' '#!/bin/sh' 'exit 0' > "$tmp/bin/accept-all"
cat > "$tmp/bin/mock-grammar" <<'MOCK'
#!/bin/sh
set -eu
was_check=no
if [ "${1:-}" = --check ]; then shift; was_check=yes; fi
bad=no
if grep -q 'ODB1_SYNTACTICALLY_IMPOSSIBLE' "$1"; then bad=yes; fi
changed=no
if grep -q 'db:wal-morph-' "$1"; then changed=yes; fi
mode=${MOCK_MODE:-reject-invalid}
if [ "$mode" = reject-both ]; then exit 1; fi
if [ "$bad" = yes ]; then
  case "$mode" in
    accept-all) exit 0 ;;
    accept-check) if [ "$was_check" = yes ]; then exit 0; else exit 1; fi ;;
    accept-runtime) if [ "$was_check" = no ]; then exit 0; else exit 1; fi ;;
    timeout-like) exit 124 ;;
    fatal-signal) exit 139 ;;
    reject-invalid|fixed-valid-output|reject-morph) exit 1 ;;
    *) exit 99 ;;
  esac
fi
if [ "$changed" = yes ] && [ "$mode" = reject-morph ]; then exit 1; fi
if [ "$was_check" = no ]; then
  if [ "$mode" = fixed-valid-output ]; then
    printf '%s' 'db:wal-roundtrip-ok'
  else
    sed -n 's/.*stdio\.stdout\.write("\([^"]*\)").*/\1/p' "$1" | tail -n1 | tr -d '\n'
  fi
fi
exit 0
MOCK
chmod 700 "$tmp/bin/accept-all" "$tmp/bin/mock-grammar"
run_challenge() {
  exe="$1"; digest=$(sha256sum "$exe" | cut -d ' ' -f1)
  (cd "$tmp" && ORESLANG_BIN="$exe" ORESLANG_COMPILER_SHA256="$digest" \
    sh ci/verify-native-wal-negative-challenge.sh)
}
reject_challenge() {
  expected="$1"; exe="$2"
  if run_challenge "$exe" > "$tmp/out" 2> "$tmp/err"; then
    echo 'HOST_NEGATIVE_SOURCE_TEST_FAILED: accepted invalid compiler' >&2
    exit 1
  fi
  if ! grep -Fq "$expected" "$tmp/err"; then
    echo "HOST_NEGATIVE_SOURCE_TEST_FAILED: wrong rejection stage, expected $expected" >&2
    cat "$tmp/err" >&2
    exit 1
  fi
}
# Every test must reach the intended validation branch, not merely fail early.
MOCK_MODE=accept-all; export MOCK_MODE
reject_challenge NATIVE_COMPILER_MALFORMED_CHECK_NOT_PROVED "$tmp/bin/mock-grammar"
MOCK_MODE=accept-check; export MOCK_MODE
reject_challenge NATIVE_COMPILER_MALFORMED_CHECK_NOT_PROVED "$tmp/bin/mock-grammar"
MOCK_MODE=accept-runtime; export MOCK_MODE
reject_challenge NATIVE_COMPILER_MALFORMED_RUN_NOT_PROVED "$tmp/bin/mock-grammar"
MOCK_MODE=timeout-like; export MOCK_MODE
reject_challenge 'NATIVE_COMPILER_MALFORMED_CHECK_NOT_PROVED status=124' "$tmp/bin/mock-grammar"
MOCK_MODE=fatal-signal; export MOCK_MODE
reject_challenge 'NATIVE_COMPILER_MALFORMED_CHECK_NOT_PROVED status=139' "$tmp/bin/mock-grammar"
MOCK_MODE=reject-both; export MOCK_MODE
reject_challenge NATIVE_COMPILER_CANNOT_CHECK_VALID_TEMP_COPY "$tmp/bin/mock-grammar"
MOCK_MODE=fixed-valid-output; export MOCK_MODE
reject_challenge NATIVE_COMPILER_WRONG_METAMORPHIC_STDOUT "$tmp/bin/mock-grammar"
MOCK_MODE=reject-morph; export MOCK_MODE
reject_challenge NATIVE_COMPILER_CANNOT_CHECK_METAMORPHIC_COPY "$tmp/bin/mock-grammar"
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
printf '%s\n' 'HOST_NATIVE_NEGATIVE_CHALLENGE_CONTRACT_PASS cases=11; MOCK COMPILERS ONLY; NO GUEST EXECUTION'
