# Stage 11 — host shard WAL-before-publication gate and native actor admission shell

This is **not a durable Oreslang database or a guest-visible ACK**. This stage connects a pure host in-memory key/value index to the already existing package-private WAL sync boundary, but real actor scheduler integration and native compiler execution are still missing. The Oreslang `DbShardActor` is intentionally fail-closed: it rejects every message until an independently authenticated runtime authority can be injected outside actor DATA messages.

## Runtime shape

1. A real runtime would issue `WalRuntimeGrant.Context` for a specific tenant/isolate/shard/activation generation and confine an **exclusive preopened WAL descriptor**. No caller may construct that authority from a received integer, string, or path.
2. `ShardPublicationGate.fromTrustedRuntime(context, grant, authority)` creates a **host-only** shard publication gate for a **fresh empty WAL**. It requires a real `RuntimeAuthority.isCurrent` check at every actor-turn operation; passing an always-true fake in production is forbidden. The gate itself cannot attest serialized turns or trusted runtime provenance.
3. `stageAndDispatch` validates the exact ODB1 frame, owner identity, sequence and per-shard limits; keeps a defensive clone as PENDING; submits through the existing `WalRuntimeGrant.appendAndSync` off-carrier writer. Exactly **one** write may be outstanding. No map/index mutation or readable success occurs at this stage.
4. The **real runtime must route the CompletionStage through `WalActorContinuationGate`** to a new serialized actor turn. Direct callbacks on the disk thread, late completed-stage registration, or arbitrary caller threads must not execute guest actor code.
5. `publishOnTrustedTurn` verifies exact Context **object identity**, activation currency, operation ID, sequence, absolute WAL end offset and SHA-256 of the frozen submitted frame against the **private host `SyncedBoundary`** before publishing host-local PUT/DELETE changes. A malicious or mismatching receipt poisons the gate as UNKNOWN.
6. A post-dispatch failure, cancellation, lost callback, activation retirement or mismatching sync becomes **UNKNOWN**, never ABORTED or auto-retried. The gate then rejects reads, further submissions and late publication; recovery must be independently authorized and reconciled with the same operation ID.
7. All externally returned values are defensive copies. `canAcknowledgeGuest()` is **always false**; even a real host `FileChannel.force(true)` receipt is not sufficient to assert an Oreslang guest DB commit.

Limits inherit native ODB1 goals: 1 MiB WAL per fresh shard, 4,096 operations, 128 lifetime key slots, 128 KiB live values, 1..64-byte keys, 0..1,024-byte values, ODB1 checksum and monotone sequence. Tombstones preserve key-slot identities. History, watermark, and idempotency are **only in memory**; the class rejects opening a recovered/nonempty WAL until a separately proven replay-to-writer transition exists. No SQL/MVCC/copy-on-write pages in this stage.

## Verification and evidence

Local Java 21 gate implementation was compiled with `javac -Xlint:all -Werror` and a **separate test-only WAL-grant harness** that uses a real temporary file, synchronous `FileChannel.force(true)` and host-local synthetic `SyncedBoundary` objects. **37/37 checks x5 PASS**, including no early visibility, ordered PUT/overwrite/DELETE, anti-alias read copies, wrong tenant/generation, wrong Context identity, same-operation rejection, sequence gaps, immutable submitted frame, UNKNOWN after dispatch, late receipt rejection and stale activation poisoning.

**Important test limitation:** that local run does *not* compile the production `WalFilePrimitive.java`/`WalRuntimeGrant.java` from GitHub and does *not* prove the real `WalActorContinuationGate` schedules callbacks. Do not report 185/185 local harness assertions as production source-integration evidence.

The new GitHub workflow `.github/workflows/db-shard-publication-host.yml` is independently pinned to the exact four production/test Git blobs. It compiles the **real production** WAL primitive and grant together with this gate on JDK21 and runs its test five times, but it only counts as evidence after nonzero GitHub Actions job steps actually execute. Source org zero-step failures are runner/admission failures, not code tests.

## Remaining P0 gates

- Security review of the actual `WalRuntimeGrant` context issuance boundary, independent descriptor exclusive lifetime pin and real `RuntimeAuthority` + actor scheduler integration in Java/Graal; same contract in LLVM.
- Compiler/guest checks for `src/shard/shard_actor.ores`, sealed state and message admission. The actor currently rejects all DATA: **there are no guest get/put/delete APIs**.
- WAL replay and persistent cross-restart operation-ID resolution; validation of recovery manifest *and actual canonical index image*; checkpoint write, file fsync, rename and parent directory dirsync; crash and power-loss fault injection.
- Explicit guest committed ACK contract only after durable WAL sync, runtime fenced publication and verified recovery. Do not convert the host-local `Published` record into an ACK.
- Private cross-org source transfer under issue #15 still awaits separate independent reviewer approval of exact source tree/destination/collaborator and cleanup scope. No private source was copied to another org in this PR.

Keep **draft**, do not merge, claim durable database readiness, or release.
