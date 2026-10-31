# Stage 3: bounded runtime-owned WAL file primitive (host-only)

**Status:** a minimal Java 21 **host primitive**, not a working Oreslang
filesystem capability, storage actor, WAL actor, database, or guest execution.
This independent source slice builds on the pure `.ores` WAL codec/replay and
fail-closed storage gate of PRs #6 -> #7 -> #8 -> #9, tracking issue #4.
The Java code is a backend primitive only; durable database semantics must
remain in `.ores` and be exercised using a pinned, real Oreslang compiler.

## Implemented in `runtime/java`

`WalFilePrimitive` is package-private and has no guest interop binding. A
trusted host must **preopen and authorize a FileChannel**, acquire an exclusive
per-tenant/isolate/shard/generation lease, securely confine its file and
parent directory, and ensure the WAL file name is durable (parent dirsync)
**before** passing it to `fromRuntimeFile`. It deliberately accepts NO
filesystem path, guest-written capability, or arbitrary integer as IO
authority. This entrypoint is not yet wired to the Java/Graal Oreslang runtime;
future bridging must never export this class or its constructor to guest code.

- One dedicated FIFO disk worker per owner, independent of actor carriers,
  and a distinct completion worker for callback handoff. Rejected admissions
  never execute disk I/O on the submitting actor thread. The backend only
  offers `appendAndSync`: fully validating a v1 frame and defensive-copying
  its bytes, positional append, `FileChannel.force(true)`, *then* producing an
  immutable package-private `SyncedReceipt` containing owner, sequence,
  operation ID, end offset, and SHA-256 frame digest.
- Maximum 32 outstanding submissions including handoffs, 1 MiB WAL bytes
  until recovery/checkpoint exists, 4,096 operation IDs per lifetime, exact
  expected sequence and generation, bounded WAL record lengths. No implicit
  sequence wraparound, silent op-id reuse or caller-runs I/O on saturation.
- An uncertain write/sync or external WAL length change poisons the owner,
  retains potentially written bytes, does not issue success, and refuses all
  further submissions. Queued operations after a poisoning must not write.
  Revocation stops new admissions, and accepted but unreconciled operations
  may complete with `UnknownOutcome` after disk effects. No automatic retry,
  ACK, truncation, or claiming a revoked operation definitely canceled.
- Nonempty WAL files fail closed on open; verified `.ores` stream replay,
  durable ID history, exclusive recovery, snapshots, and generation-fenced
  resume are required **before** native append-to-existing-log is enabled.
  This is intentional: opening a previous WAL without replay cannot be safe.
- Native Java tests validate independent exact 32-byte v1 golden vector,
  rejected frame/owner/sequence, duplicate IDs, defensive copy, bounded
  admission, forced sync failure, fail-closed queued-work poisoning,
  callback handoff outside disk worker, revocation, real file reopen, handoff-queue rejection without running guest callbacks on the disk worker,
  and a **separate JVM forcibly terminated after `FileChannel.force(true)`**.
  Local test: `javac -Xlint:all -Werror` and `java` complete with
  `HOST_JAVA_PRIMITIVE_PASS assertions=80`. A clean exact-head CI run must
  instead use `ci/verify-java-storage-primitive.sh` and supply independent
  HEAD/tree and `java`/`javac` executable digests.

## What this test does **not** prove

`FileChannel.force(true)` reports OS file sync completion, but cannot by
itself prove parent-directory persistence, protection from hostile directory
replacement, exclusive native lock acquisition, full power-loss durability,
or handling of every filesystem/storage-stack failure. A process-kill after
sync is a *host primitive process-kill smoke*, not power failure, durable
DB commit, or Oreslang runtime execution. `IoHooks` is a package-local
fault-injection hook; production `fromRuntimeFile` always calls the built-in
`force(true)` implementation. **Never register fault injection as a guest or
production runtime API.**

`Owner` and `SyncedReceipt` are Java host values, not unforgeable guest
capabilities or trusted host attestations when copied outside the backend.
The trusted runtime must bind the owner to its actual actor, isolate,
incarnation, native file identity and directory FD. A guest cannot assert
that the on-disk WAL was synced by supplying equivalent numeric IDs or a
checksum. The WAL actor must re-check the runtime fence on completion before
any `Committed` result; a returned `SyncedReceipt` is **not** a DB ACK.

## Hard prerequisites before promoting this PR

1. Connect a runtime-created opaque, non-DATA-sendable async filesystem
   capability to the Oreslang actor scheduler; ensure no FileChannel or Java
   host classes are directly accessible as guest authority. Implement
   directory-relative race-resistant open, shard lock/lease, durable file
   creation+parent fsync, and safe revoke/quiescence with no callback escaping
   its owning actor generation.
2. Implement native `.ores` `WalActor` using `state` + `handle_state` +
   `critical` for brief state transitions; **no await inside a critical loan**.
   Flow: authorize -> reserve -> release loan -> await trusted appendAndSync
   -> verify owner/generation/digest/op ID/sequence -> update index and durable
   receipt ledger -> acknowledge. Late revocation and lost replies are
   `Unknown(op_id)` until replay-based reconciliation.
3. Implement bounded streaming `.ores` replay with cross-window durable
   idempotency history; do not trust `pending_tail` as EOF or authorize
   truncation based on checksum and a memory-window boundary. Quarantine
   complete corruption.
4. Implement checkpoint temp-write/sync, atomic rename, parent dirsync,
   WAL retention/rotation, fault injection (including ENOSPC/partial
   write/force/rename/sync errors), and independent-process crash windows.
5. Execute compiler-pinned `.ores` guest fixtures and native storage tests on
   exact head/tree with nonzero runner steps. Keep host Java 21, guest,
   metadata-receipt, and real durability evidence separate. Obtain
   independent security review, protected `main`, and backend parity.

**Do not merge/release as working DB. No cross-org private-source copy.**
