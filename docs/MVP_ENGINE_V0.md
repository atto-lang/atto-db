# Oreslang DB: smallest durable KV milestone (Issue #4)

**Status: P0 contract with one pure Oreslang WAL frame-codec source slice.**
No disk I/O, actor state machine, compiler-backed run, recovery or durability
has been demonstrated by this change. A Java/JDBC PostgreSQL host client and
an offline receipt inspector are separate draft PRs (#2 and #3).

## Exact scope and planned source layout

```text
src/wal/frame.ores                  PRESENT: bounded v1 single-record encode/decode
src/storage/capability.ores         TODO: opaque runtime-granted storage authority
src/storage/storage_actor.ores      TODO: isolated async append/sync/rename/fence
src/wal/wal_actor.ores              TODO: non-reentrant WAL owner/recovery
src/index/index_actor.ores          TODO: bounded per-tenant in-memory index
src/query/query_actor.ores          TODO: typed get/put/delete API/coordinator
src/db.ores                         TODO: public API & lifecycle
tests/guest/positive/frame-roundtrip.ores    PRESENT
tests/guest/negative/frame-rejections.ores  PRESENT (runtime rejects)
tests/guest/positive/frame-boundaries.ores PRESENT (high checksum and lengths)
tests/guest/recovery/*.ores         TODO: actual async storage capability
tests/native/fault-injection/*      TODO: real crash/persistence provider
ci/verify-native-wal.sh             PRESENT: fail-closed guest compiler gate
```

The eventual storage, WAL, index and query actors use
`define isolated actor ... as ... end`. Every mutable actor-instance field
must live under the single `state` field, and mutation must be a sealed
`handle_state` operation inside `critical`. Handlers must not re-enter
across suspension. Validate tenant capability, isolate ID, actor activation
generation and bounded budget **before** work is admitted. Neither ordinary
ints in a WAL frame nor guest-constructed objects are security capabilities.
Storage access is granted by the runtime, not by language-level Java interop.
No blocking I/O or suspension in a critical region.

### Protocol v0 (single node, one WAL owner per shard)

- Only typed `get(tenant,key)`, `put(tenant,key,value,op_id)`,
  `delete(tenant,key,op_id)`, and `resolve(op_id)`. PUT/DELETE return
  `Committed(sequence)`, `Rejected(code)`, or `Unknown(op_id)`;
  UNKNOWN is **never** silently retried with a new ID.
- Limits: key 1..64 bytes; value 0..1024 bytes (delete requires zero);
  <= 4096 live keys/shard; <= 32 outstanding operations/shard counting queued
  plus in-flight; <= 1 MiB WAL replay window per admission chunk. Overflow
  means an explicit refusal, not wraparound. Bounded replay is streaming.
- Append a fully validated WAL frame using the granted async storage
  capability. Wait for durable file sync before ACK. The index may publish
  a committed mutation only after the durable point; if ACK is lost,
  `resolve(op_id)` consults durable replayed receipts. No I/O or await
  occurs while the actor state's critical loan is held.
- WAL owner serializes per-shard seq assignment and per-shard commit
  decisions; state changes are fenced by activation generation.
  Pre-durable cancellation of a queued operation is `Cancelled`.
  Cancellation after physical dispatch returns `Unknown` until resolution.
- Checkpoint: write bounded generation N+1 snapshot to temp on same
  filesystem, sync temp, rename atomically, sync parent directory,
  retain durable WAL needed by prior generation until verified; replay from
  last valid checkpoint. Never truncate old WAL based solely on rename.

### WAL frame v1 present in `src/wal/frame.ores`

```text
offset  bytes  field
0       4     ASCII ODB1 magic
4       1     version=1
5       1     kind=1 PUT / 2 DELETE
6       4     tenant numeric internal ID (not an authority)
10      4     activation generation
14      4     per-shard positive sequence
18      4     positive idempotency op ID
22      2     key length
24      2     value length
26      K+V   bounded raw bytes
26+K+V  4     Adler32(header+payload), little endian
```

All integers little-endian. Numeric identifiers 1..2147483647. Total
record length 31..1118 bytes. Adler32 is an **accidental-corruption
detector**, not an authentication code or protection against an attacker
who can edit bytes; secure admission requires an opaque capability.
Future incompatible wire changes require a new version, not silent
reinterpretation. Unknown versions and operations fail closed.

The pure codec validates single record length, bounds, marker, version,
operation type, numeric ranges, bytes and checksum before allocating output.
Adler-32 writes and verifies A/B as two little-endian u16 limbs, avoiding
signed-int32 overflow on checksums greater than 0x7fffffff. IDs with their
high bit set are rejected before signed 32-bit deserialization; decoded
identities remain limited to 1..2147483647. Do not use the checksum as a MAC.
It does not parse a WAL stream, retain durable idempotency receipts, detect
sequence gaps, authenticate tenants, handle checkpoints or prove fsync.

## Exact pinned guest-codec cases

Candidate compiler **source revision**:
`ores-truffle-oreslang/oreslang-source.java@241bd67c39443cc2ef18df7e4ed456454af378c7`.
This is a *pin proposal*, not proof that any binary corresponds to it.
Provision an independent binary, verify its source provenance and
SHA-256, and set `ORESLANG_BIN` and `ORESLANG_COMPILER_SHA256`.
Independently record the *reviewed* exact HEAD commit and tree into
`DB_EXPECTED_COMMIT` and `DB_EXPECTED_TREE`; do not derive the expected
pins from the checkout inside the same verification script. The script
checks both pins, working-directory root, clean status and binary SHA256
before compiling any fixture. Execute `sh ci/verify-native-wal.sh` on
that clean exact HEAD. A compiler source commit alone is not an executable
binary attestation.
Each case requires successful compiler `--check`, exit=0 from guest,
and byte-for-byte stdout **without a terminal newline**.

| Class | Existing fixture | Exact stdout | Status |
| --- | --- | --- | --- |
| Positive | `tests/guest/positive/frame-roundtrip.ores` | `db:wal-roundtrip-ok` | NOT_EXECUTED |
| Runtime-negative | `tests/guest/negative/frame-rejections.ores` | `db:wal-rejections-ok` | NOT_EXECUTED |
| Boundary-positive/negative | `tests/guest/positive/frame-boundaries.ores` | `db:wal-boundaries-ok` | NOT_EXECUTED |

Negative fixture checks torn trailer, payload byte mutation, unknown kind,
zero tenant/generation/sequence/op ID, nonempty delete value, overlong key,
overlong value. The boundary fixture covers a high-bit Adler checksum,
1,024-byte value, 1,118-byte maximum legal frame, 31-byte DELETE,
2,147,483,647 identity limit, forbidden signed high-bit ID, trailing byte,
and malformed byte values. A test's successful *rejection* is not compiler-negative
evidence, or a durable-storage recovery proof. The runner has no fallback,
does not auto-fetch a compiler or use a host-language WAL substitute.

## Full engine acceptance matrix: exact required oracles (NOT_IMPLEMENTED)

The provider/harness must print the stated exact ASCII stdout without
newline, return code 0 only on all asserted checks and use the specified
`tests/guest` or `tests/native` path when implemented. A native crash
harness must kill a **separate process**, reopen the *same* test database
directory with a fresh actor generation, and inspect real persisted bytes.
The test provider must record before/after bytes, sync/rename boundaries
and injected fault point, not just return a mock success.

| Case/path suffix (future) | Expected stdout | Required observation |
| --- | --- | --- |
| `positive/clean-put-get-delete.ores` | `db:kv-basic-ok` | clean empty open, write, exact get, delete, missing |
| `positive/restart-replay.ores` | `db:replay-ok` | committed key persists under new process |
| `positive/same-op-replay.ores` | `db:dedupe-ok` | identical op ID has one effect/seq |
| `positive/ordered-writers.ores` | `db:ordered-ok` | concurrent actors observe monotonically committed seq |
| `negative/cross-tenant.ores` | `db:tenant-denied` | both cross-tenant read and write denied |
| `negative/stale-generation.ores` | `db:stale-denied` | old activation owner can't commit |
| `negative/no-capability.ores` | `db:capability-denied` | absent/forged I/O capability denied |
| `negative/queue-full.ores` | `db:queue-full` | 33rd outstanding op refused (max 32) |
| `negative/oversized-input.ores` | `db:limits-denied` | key/value/buffer/overflow fail closed |
| `negative/cancel-queued.ores` | `db:cancel-queued` | dequeued? no; no WAL side effect |
| `negative/cancel-dispatched.ores` | `db:cancel-unknown` | explicit unknown until op-ID resolution |
| `recovery/before-append.ores` | `db:preappend-empty` | crash before append has no mutation |
| `recovery/torn-tail.ores` | `db:tail-truncated` | only final incomplete frame discarded |
| `recovery/after-write-before-sync.ores` | `db:pre-fsync-unknown` | no ACK; on restart absent OR durable then resolve |
| `recovery/after-sync-before-ack.ores` | `db:sync-reconciled` | durable exactly once, ACK absent |
| `recovery/after-sync-before-index.ores` | `db:index-replayed` | replay committed but not published mutation |
| `recovery/checkpoint-temp-crash.ores` | `db:old-checkpoint-ok` | old complete checkpoint + WAL wins |
| `recovery/checkpoint-rename-crash.ores` | `db:checkpoint-recover-ok` | either old/new fully valid generation, no loss |
| `recovery/corrupt-middle.ores` | `db:corruption-quarantined` | refuse open; never skip bad middle frame |
| `recovery/stale-checkpoint.ores` | `db:stale-replayed` | later committed WAL not skipped |

The exact stdout is only a coarse pass marker: evidence also requires
operation-level assertion transcripts, exit codes, known fault points,
run/job attempt, step count, compiler executable SHA, source/compiler tree
SHAs, filesystem/storage capability identity, and exact test input hashes.
A simulator cannot replace OS crash/fsync proof. No SQL, MVCC, replication,
distributed consensus, native PostgreSQL protocol or cross-backend parity
claim is part of v0.

## Evidence classes and stop conditions

1. **Host PostgreSQL** (#2): Java/JDBC pool and PostgreSQL 17 RLS.
2. **Receipt infrastructure** (#3): Rust format/checker consistency only.
3. **Guest compiler** (this PR): exact `.ores` compile + run; as yet absent.
4. **Durability** (future): real file-backed crash injection, fsync + dirsync,
   torn-tail vs mid-log corruption and idempotent replay.
5. **Cross-backend**: JVM/Graal, LLVM, other targets each execute independently.

The newest host-reference Actions runs #41166490651 and #41166487672
show a failed job with zero executed steps: this is runner
admission/pre-execution evidence, not a compiler or database failure.
No private source copied to a different organization, no release gate
modified, no PR merged. Keep draft until executed nonzero-step evidence
and independent approval.
