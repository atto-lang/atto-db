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

`ci/test-five-backend-preflight.sh` creates a disposable local Git repository and **mock** executable files, never a real Oreslang compiler. It exercises six shell orchestration cases: one nominal mocked matrix, missing .NET launcher, corrupted WASM executable digest, wrong exact source-commit pin, reuse of the same compiler binary/hash under Java and LLVM, and a dirty working tree. All five negative cases must fail **before** any `BACKEND_ATTEMPT` line or backend guest credit. This preflight is an integrity/consistency guard, **not compiler authenticity**: a correctly hashed arbitrary fake launcher can still produce output matching a fixture. Rejecting shared digests does not prove different code generators were used. Compiler provenance, generated artifact identity and actual backend execution require independent attestation and must not be inferred from this script.

The path-scoped `.github/workflows/db-parity-preflight.yml` runs these host-only self-tests when an Actions runner is admitted. A configured workflow or zero-step GitHub Actions failure does not count as execution. The baseline shell and Python reference checks performed while authoring the extra fixture validate shell orchestration and six recomputed-checksum vectors only, not the .ores parser or runtime.

## Stage 31 adversarial ODB1 guest corpus (authored; NOT executed)

Six additional source-identical native `.ores` fixtures extend the nine existing codec cases
to **15 cases per backend / 75 required compiler-backed guest executions**:

- Binary keys/values containing 0, 128 and 255 must retain exact bytes.
- All 32 strict prefixes of the 32-byte golden record, a trailing octet, and two
  concatenated records must be rejected as *one* ODB1 frame.
- An explicit +1/-2/+1 three-key-octet Adler-32 collision must **decode successfully
  as altered data with unchanged checksum**. This is a *security limitation oracle*,
  not a sign of authentication. A checksum cannot authorize tenant data or WAL ACK.
- Two syntactically valid records with the same operation ID and different sequences
  both decode. **Persistent idempotency and replay reconciliation belong to the
  shard ledger**, not the codec; the test must not become a deduplication claim.
- Mutating each of the four checksum-trailer octets must reject.
- A 64-octet key and 256-octet value must encode little-endian length bytes
  [64,0][0,1] and round-trip in an exactly 350-byte record.

`verify-native-wal.sh` runs all fifteen cases with byte-exact stdout and counts each
completed case before declaring a backend codec-only result. The five-backend
orchestrator requires all five externally provisioned, hash-pinned compiler adapters
and the exact DB source commit and tree, for 75 total guest invocations.
The expanded shell mock-only preflight covers ten distinct cases and **must never**
be counted as an Oreslang guest result. The compiler SHA alone does not authenticate
compiler build origin, backend identity, NativeAOT image, Wasm artifact or codegen;
independent build provenance and actual native execution receipts remain P0.

**Backend capability gap (not a waiver):** Java/GraalVM has a broad interpreter
but these exact cases still lack verified guest execution. LLVM has a narrow native
module/class/Result subset; Wasm is currently limited to pure i64 output through an
LLVM bridge; Cranelift ships scalar expression JIT; and .NET shipping NativeAOT
supports a scalar compiler, not List/Frame/module/Result imports. A backend that
cannot compile and run the unchanged guest source must FAIL, not be credited by a
host-side wire encoder or a fake launcher. The required parity target is full
source-level execution on **all five**, with separate actor/async storage and
recovery parity after the codec gate passes.

No independent approved private-source mirror or source-org nonzero-step CI has
been established by this source-only change. Keep the stack draft.

## Stage 31 provenance gate (host-only; not a backend certification)

For each of the five independently provisioned adapters, the matrix now
requires `DB_<BACKEND>_SOURCE_ROOT`, `_SOURCE_COMMIT` and `_SOURCE_TREE`,
as well as `_COMPILER_BIN` and `_COMPILER_SHA256`. Before any run is credited
it verifies each root is an absolute clean Git checkout and matches its own
40-digit exact commit/tree. A second check occurs at execution dispatch.
The mock harness creates five distinct disposable source repos and runs
**14/14** preflight cases, including wrong source commit/tree, dirty checkout,
relative root, binary digest/missing executable, duplicate binary and dirty DB tree.

**These inputs are claims, not cryptographic compiler build attestation**.
They do not prove the pinned executable came from that source commit, that a
Wasm or NativeAOT artifact was emitted, or that the exact guest ran. The
end-of-script marker is deliberately `FIVE_BACKEND_ODB1_EXECUTION_CANDIDATE_PASS`,
not a verified release/parity certificate. Independent provenance and real
execution receipts for all five backends remain mandatory.
