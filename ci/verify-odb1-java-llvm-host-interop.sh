#!/usr/bin/env bash
# Actual Java 21 production WAL replay + independently compiled native C++
# byte-format differential test. HOST ONLY, not Oreslang guest compiler parity.
set -euo pipefail
export LC_ALL=C
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
java_src="$root/runtime/java/src/org/oreslang/db/primitive/ReadOnlyWalReplay.java"
java_test="$root/runtime/java/test/org/oreslang/db/primitive/Odb1JavaWireInterop.java"
cpp="$root/runtime/llvm/test/odb1_wire_interop.cpp"
for x in "$java_src" "$java_test" "$cpp"; do [[ -f "$x" ]] || { echo "MISSING_REAL_HOST_SOURCE $x" >&2; exit 2; }; done
command -v java >/dev/null
command -v javac >/dev/null
command -v clang++ >/dev/null
command -v g++ >/dev/null
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/classes" "$work/java" "$work/native" "$work/invalid" "$work/mutants"
javac -Xlint:all -Werror -d "$work/classes" "$java_src" "$java_test"
java_cmd=(java -cp "$work/classes" org.oreslang.db.primitive.Odb1JavaWireInterop)
"${java_cmd[@]}" emit "$work/java"
checks=0
for compiler in clang++ g++; do
  "$compiler" -std=c++20 -O2 -Wall -Wextra -Wconversion -Werror -pedantic \
    "$cpp" -o "$work/native-$compiler"
  native="$work/native-$compiler"
  "$native" emit "$work/native"
  "$native" emit-negative "$work/invalid"
  "$native" emit-mutants "$work/mutants"
  for f in "$work/java"/*.wal; do
    name="$(basename "$f")"
    cmp -- "$f" "$work/native/$name" || { echo "ODB1_BYTES_DIFFER $compiler $name" >&2; exit 1; }
    checks=$((checks + 1))
  done
  # True Java production inspection vs true compiled native inspection.
  for group in java native invalid mutants; do
    for f in "$work/$group"/*.wal; do
      native_rc=0
      "$native" inspect "$f" 7 > "$work/native.out" || native_rc=$?
      java_rc=0
      "${java_cmd[@]}" inspect "$f" 7 > "$work/java.out" || java_rc=$?
      if [[ "$native_rc" != "$java_rc" ]]; then
        echo "ODB1_EXIT_MISMATCH $compiler $group/$(basename "$f") native=$native_rc java=$java_rc" >&2; exit 1
      fi
      cmp -- "$work/native.out" "$work/java.out" || {
        echo "ODB1_OUTPUT_MISMATCH $compiler $group/$(basename "$f")" >&2; exit 1;
      }
      # Case-specific assertions prevent two implementations making the same
      # mistake and incorrectly passing a mere differential comparison.
      case "$group/$(basename "$f")" in
        java/*|native/*|invalid/valid_adler_collision.wal) expected_rc=0; expected_line='' ;;
        mutants/*) expected_rc=1; expected_line='' ;;
        invalid/checksum_corrupt.wal) expected_rc=1; expected_line='INVALID reason=checksum_mismatch at=0' ;;
        invalid/delete_nonempty_value.wal|invalid/invalid_key_length.wal|invalid/invalid_value_length.wal) expected_rc=1; expected_line='INVALID reason=invalid_payload_length at=0' ;;
        invalid/duplicate_operation.wal) expected_rc=1; expected_line='INVALID reason=duplicate_operation at=32' ;;
        invalid/foreign_tenant.wal) expected_rc=1; expected_line='INVALID reason=invalid_identity_or_tenant at=0' ;;
        invalid/generation_regression.wal) expected_rc=1; expected_line='INVALID reason=generation_regression at=32' ;;
        invalid/high_bit_generation.wal|invalid/high_bit_op.wal) expected_rc=1; expected_line='INVALID reason=high_bit_identity at=0' ;;
        invalid/invalid_kind.wal) expected_rc=1; expected_line='INVALID reason=invalid_kind at=0' ;;
        invalid/invalid_magic.wal|invalid/invalid_version.wal) expected_rc=1; expected_line='INVALID reason=invalid_magic_or_version at=0' ;;
        invalid/sequence_gap.wal) expected_rc=1; expected_line='INVALID reason=sequence_gap at=32' ;;
        invalid/short_header.wal) expected_rc=1; expected_line='INVALID reason=unverified_short_tail at=0' ;;
        invalid/too_large.wal) expected_rc=1; expected_line='INVALID reason=wal_size_out_of_bounds at=0' ;;
        invalid/too_many_records.wal) expected_rc=1; expected_line='INVALID reason=too_many_records at=126976' ;;
        invalid/truncated_tail.wal) expected_rc=1; expected_line='INVALID reason=possible_partial_tail_no_repair_authority at=0' ;;
        invalid/unverified_short_tail.wal) expected_rc=1; expected_line='INVALID reason=unverified_short_tail at=32' ;;
        *) echo "UNCLASSIFIED_FIXTURE $group/$(basename "$f")" >&2; exit 1 ;;
      esac
      if [[ "$native_rc" -ne "$expected_rc" ]]; then
        echo "ODB1_WRONG_DIAGNOSTIC_STATUS $compiler $group/$(basename "$f")" >&2; exit 1
      fi
      if [[ -n "$expected_line" ]] && [[ "$(cat "$work/native.out")" != "$expected_line" ]]; then
        echo "ODB1_WRONG_DIAGNOSTIC_REASON $compiler $group/$(basename "$f")" >&2; exit 1
      fi
      checks=$((checks + 1))
    done
  done
  # Both backends must fail closed on a valid record assigned to another tenant.
  native_rc=0
  "$native" inspect "$work/native/single.wal" 8 > "$work/native.out" || native_rc=$?
  java_rc=0
  "${java_cmd[@]}" inspect "$work/native/single.wal" 8 > "$work/java.out" || java_rc=$?
  [[ "$native_rc" -eq 1 && "$java_rc" -eq 1 ]]
  cmp -- "$work/native.out" "$work/java.out"
  grep -q 'INVALID reason=invalid_identity_or_tenant at=0' "$work/native.out"
  checks=$((checks + 1))
  # A complete file must replay identically after separate process exits.
  "$native" inspect "$work/java/two.wal" 7 > "$work/first.out"
  "$native" inspect "$work/java/two.wal" 7 > "$work/second.out"
  cmp "$work/first.out" "$work/second.out"
  checks=$((checks + 1))
done
printf 'ODB1_JAVA_NATIVE_HOST_INTEROP_PASS checks=%d compilers=clang++,g++; REAL JAVA+CPP EXECUTED; NO ORESLANG GUEST OR BACKEND PARITY CLAIM\n' "$checks"
