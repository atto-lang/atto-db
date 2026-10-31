# DB stage 5 — transaction ownership before durable commit

**Status: draft / source-only / NOT executed.** This is a source-level single-record transaction gate stacked on DB PR #11 (the host-only runtime grant seam). It is not a usable SQL transaction, database engine, or authenticated durable write API.

## The necessary WAL scope restriction

ODB1 v1 frames encode one PUT/DELETE with a single operation ID. They have no transaction begin/commit record, transaction atomicity marker or multi-key rollback mechanism. A v1 frame's file sync cannot atomically commit two records. Accordingly this stage allows **one WAL record per transaction** and exports **no Committed/ACK transition**. Multi-key support must first specify and test a versioned commit-record protocol with checksum/length bounds, replay atomicity, idempotency and crash ordering. Do not retrofit an unsafe multi-record commit claim into ODB1.

## Stage-5 source

- `src/transaction/single_record_gate.ores`: per-transaction bound tenant/generation/ID; validates one operation with ODB1 frame size 31–1118 B; phases OPEN (0), STAGED (1), SEALED (2), DISPATCHED (3), UNKNOWN (4), ABORTED (5). Does not allocate a WAL sequence or accept a guest-created receipt. Only OPEN can stage; no replacement, replay, second operation or unbounded queue.
- Cancellation/close before dispatch proves only that this local gate has not handed anything to I/O; the runtime must establish atomic handoff and own the external dispatch fence. After dispatch, neither cancellation nor close may claim abort, release the record's held bytes, retry blindly or acknowledge a commit. No guest-accessible method can turn a numeric token into a durability receipt.
- `src/transaction/transaction_actor.ores`: provisional `define isolated actor` with `state` and critical `handle_state`; currently rejects every DATA message. The gate is a local helper intended to live in one actor's sealed state, not a transferable capability.
- `tests/guest/positive/single-record-txn-lifecycle.ores` and `tests/guest/negative/single-record-txn-rejections.ores`: exact stdout oracles for single-record lifecycle, stale identities, duplicate stage, sequence of transitions, early cancellation, dispatch/unknown retention and ACK prohibition.
- `ci/verify-native-single-record-txn-stage5.sh`: inherits all stage-4 native checks and the independent exact checkout/tree/compiler pins; compiles both transaction sources and runs both fixtures with exact output. No mock compiler, host simulator or automatic cross-org source copying.

## Next implementation blockers

1. Compiler-validate and execute all inherited plus two new .ores fixtures at exact head and tree; record actual stdout, compiler binary SHA256, workflow attempt and nonzero executed step identities. Fix parser/typechecker errors, do not claim they passed.
2. Integrate trusted storage authority with Java/Graal and LLVM isolates, actor activation generation, sealed ownership and kernel FD lifetime. A numeric tenant/gen/txn ID is **not authentication**.
3. Implement actual runtime handoff with append, forced sync, ordered and authenticated synced receipt, actor resumption without holding a critical state loan, commit publication and only-then ACK; reconcile ambiguous cancellation/retirement outcomes.
4. Add genuine WAL streaming replay, persistent idempotency, checkpoint recovery and fault injection for crash before append, append/sync boundary, sync/ACK boundary and corrupt mid-log.
5. Introduce a separate ODB2 transaction protocol for atomic multi-record commits, MVCC/snapshot pinning and cross-shard transactions **after** single-record durability is proven.
6. Require independent security review and protected source main before promotion. Keep draft; no private cross-org mirror without explicit authorization.

## Evidence boundary

The pure gate tracks intent and ownership metadata only. It cannot verify the storage callback or the actual file sync; any success of these fixtures would prove **guest state-machine execution**, not disk durability, transaction commit, SQL functionality, or Java/LLVM parity. There are no new host filesystem tests in this stage.
