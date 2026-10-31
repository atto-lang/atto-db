# Native Oreslang DB storage-owner admission stage 1

**STATUS: unexecuted source-level proposal, fail-closed; no durable filesystem backend, no write ACK, no committed database.** Stacked on WAL codec PR #7 (which itself stacks on PR #6). Do not merge or advertise a working actor/database until guest compiler and runtime capabilities are verified.

## Present

- `src/storage/capability.ores`: **metadata validation**, not a capability. Checks tenant/generation/op-id and 31..1118-byte WAL v1 frame bounds. Numeric IDs may never authorize I/O.
- `src/storage/gate.ores`: owner-private bounded 32-slot admission state, max 35,776 reserved bytes, exactly one dispatched operation, duplicate in-flight op-id rejection, single-release queued cancellation, stale generation rejection, indefinite fence for unknown post-dispatch outcome.
- `src/storage/storage_actor.ores`: *provisional* `define isolated actor` with one sealed `state` schema and `self.handle_state(critical-write |s| -> ...)`. All incoming messages are rejected and it never sends commit ACK. Exact-tree source parsing and actor-state lowering are **NOT_EXECUTED** and may be unsupported.
- `tests/guest/positive/storage-admission-contract.ores`: pure guest admission/negative oracle; no storage or authority claim.

The gate's public constructor and numeric operation IDs make it a **local accounting helper only**. It is neither an unforgeable bearer capability nor a durable receipt. It MUST NOT be sent as DATA, called by untrusted actors as if it granted privileges, or used to authorize filesystem access.

## Missing trusted storage runtime: mandatory API/semantics, NOT IMPLEMENTED

The actor must receive an opaque, runtime-created, non-serializable, non-DATA-sendable, per-tenant and per-shard storage handle bound to isolate and activation generation. The runtime, not guest input, owns the canonical directory FD, validated regular file FD, permission policy, and close/quiescence lifecycle. Reject symlinks, path traversal and stale incarnations using directory-relative race-resistant opens where supported. User-controlled paths and integers are not handles.

The required awaitable provider operations must be typed, byte- and time-bounded, off actor carriers, and require authenticated per-call owner fences:

1. `append(frame, op_id)`: append the exact validated bounded frame to the shard WAL under one writer. Partial writes, EINTR, ENOSPC, broken FS, and partial-success-but-timeout become explicit **Unknown**, never silently retried with a new op-id.
2. `sync_data()`: independently prove fdatasync/fsync has returned on the WAL file for the appended bytes. Sync errors leave unknown outcome; do not acknowledge an operation based on `fs.append_text`, `fs.write_text`, a fake provider, or asynchronous write scheduling.
3. `read_at` + `file_length`: bounded streaming recovery. Replay only complete verified frames; truncate only a demonstrably incomplete final tail after an exclusive fenced recovery lease. A corrupted middle frame fails closed. Preserve durable operation-ID history across checkpoints before permitting reuse.
4. `checkpoint_temp` / `sync_temp` / `atomic_rename` / `sync_parent_dir`: same-filesystem snapshot generation swap; retain prior WAL/snapshot until success, reject stale checkpoints.
5. `close` / `revoke`: no duplicate FD release, no freed actor region while native I/O callbacks are live, and no post-revocation completion granting authority.

Do not allow a guest-created object, public constructor, magic int, or checksum to serve as an authority/receipt. The provider must return a runtime-attested operation receipt that binds the actual storage handle, tenant, shard, actor/incarnation, op-id, WAL frame digest, sequence, and completed sync boundary. A guest can **only** advance the durable watermark and emit `Committed` once that trusted receipt is verified. An interrupted/disconnected request remains `Unknown(op_id)` and must be reconciled against durable replay. The present source intentionally exposes **no commit/ACK method**, so it cannot accidentally claim durability.

## No suspended state loans

The intended owner flow is: authenticate external context; reserve slot under `handle_state(critical-write)`; release state loan; submit asynchronous `append`; await its completion; await `sync_data`; verify provider receipt; briefly re-acquire state loan with matching generation/op ID; publish/release resources; then ACK. No await or filesystem call inside a critical section. The current provisional actor only closes on DATA input and exercises none of that flow.

## Acceptance evidence still required

- Obtain a compiler binary independently tied to a pinned source SHA and SHA-256. On an exact source head+tree: `--check` the actor and policy/gate, run WAL golden/negative/boundary and storage admission guest fixtures, inspect stdout/exit codes and nonzero runner steps.
- Dedicated parser/typechecker negatives: reject `self.state` field access, forged `handle_state`, mutable actor fields outside state, async await inside critical, attempted DATA transfer of storage authority, unauthorized manifest.
- Real per-OS-process crash harness: before append, torn tail, after append before sync, after sync before ACK, after sync before index publication, partial sync failure, corrupt middle, checkpoint temp crash, rename/dirsync crash, stale generation.
- Real multi-actor adversarial tests: 33rd outstanding operation denied, bytes saturation, duplicate ID, queued cancellation releases exactly once, dispatched cancellation not definitely canceled, unknown holds fence, closed actor cannot resume, tenant/isolate fencing.
- LLVM zero-GC resource and arena lifetime tests; Java/GraalVM and LLVM executable differential evidence. Source-level fixtures are not proofs of host FS durability, nor of other backends.

## Merge/release policy

Do **not** merge this source slice or its prerequisites until compiler compatibility, typed runtime storage authority, and independently approved CI security gates pass. Source-org Actions previously had admission failures; a cross-org private mirror needs separate authorization and exact Git tree/blob SHA verification. No source transfer is made in this PR.
