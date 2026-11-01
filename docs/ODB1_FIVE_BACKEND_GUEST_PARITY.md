# ODB1 five-backend guest codec parity — authored gate, not executed

Nine source-identical native Oreslang guest cases are now declared by `ci/verify-native-wal.sh`:
1. Existing exact independent 32-byte PUT round-trip.
2. Existing torn, corrupted, wrong-kind, wrong-identity and length rejections.
3. Existing full-size 1118-byte, signed-limb checksum and 31-byte DELETE bounds.
4. **New** signed-int32 maximum identities and four high-bit-u32-forgery rejection cases.
5. **New** every one of the 32 ODB1 wire bytes independently incremented, all 32 must fail decode; accidental checksum protection only, not a MAC.
6. **New** exact PUT/DELETE minimum frame and 64-byte key/1024-byte value maximum; 65-byte key, 1025-byte value, empty key and DELETE payload refusal.
7. **New** seven syntactically malformed complete headers with Adler32 recomputed after tampering (version, kind, high-bit identity, key/value length and magic), proving structural rejection independent of checksum mismatch.
8. **New** caller-owned mutable key/value arrays do not change encoded bytes; mutating encoded bytes after decode cannot alter decoded copies.
9. **New** independently invalidate all four identity fields with zero, the nonempty-key invariant, and the 1024-byte value maximum, recomputing valid Adler32 after every forged header; all six structurally illegal records must reject despite a valid checksum.

**Promotion gate:** `ci/verify-five-backend-wal-parity.sh` demands independently pinned exact OresDB commit/tree and five independently SHA256-attested compiler adapter executables: Java/Graal, LLVM, WASM, Cranelift and .NET. Every adapter must accept the identical `.ores` guest source and compile-check plus execute it with byte-exact stdout and no newline. Missing adapter, unsupported syntax, unsigned CLI semantics, missing binary hash, nonzero compiler exit, absent guest oracle or any skipped backend fails the entire matrix. A compiler's native Wasm or NativeAOT packaging must be bound by an adapter to its produced binary digest. **Do not simply run a host reference program and mark a native backend as passed.** Cranelift currently has limited scalar support, so it is a known parity gap until its actual frontend/stdlib/module runtime executes these fixtures.

Five targets × nine cases = **45 required compiler-backed guest executions**; no backend has been credited yet. This change authors source and test gates, but CI source org may fail runner admission with `steps=[]`. Do not claim OresDB durable WAL/ACK/OS fsync or runtime-capability parity from a codec fixture. The live engine requires separately verified generation-fenced actor runtime and replay under each backend. Private-source cross-org transfer remains blocked pending independently approved exact-tree and destination scope in [issue #15](https://github.com/ores-truffle-oreslang/oreslang-db/issues/15). KEEP DRAFT.

## Host-only coordinator regression tests (distinct from guest evidence)

`ci/test-five-backend-preflight.sh` creates a disposable local Git repository and **mock** executable files, never a real Oreslang compiler. It exercises five shell orchestration cases: one nominal mocked matrix, missing .NET launcher, corrupted WASM executable digest, wrong exact source-commit pin and dirty working tree. All four negative cases must fail **before** any `BACKEND_ATTEMPT` line or backend guest credit. This preflight is an integrity/consistency guard, **not compiler authenticity**: a correctly hashed arbitrary fake launcher can still produce output matching a fixture. Compiler provenance, generated artifact identity and actual backend execution require independent attestation and must not be inferred from this script.

The path-scoped `.github/workflows/db-parity-preflight.yml` runs these host-only self-tests when an Actions runner is admitted. A configured workflow or zero-step GitHub Actions failure does not count as execution. The baseline shell and Python reference checks performed while authoring the extra fixture validate shell orchestration and six recomputed-checksum vectors only, not the .ores parser or runtime.
