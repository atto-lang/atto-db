# Stage 19 — ODR1 persistent operation *evidence*, not delivery/ACK results

**Host-only DRAFT.** This is a narrow, fail-closed operation-ID reconciliation slice, stacked on Stage18 PR #26. It does **not** enable a recovered WAL writer, mutable guest actor, committed client ACK or retransmission. An ODR1 record proves only that an operation with a given ID and request fingerprint appears in a separately verified complete ODB1 WAL snapshot.

## Safety model

1. The *independently authenticated* Java/Graal runtime must issue a `DbShardLifetimeCustody.Lease`, already holding the actual WAL and both ODF1 metadata FileChannels under exclusive OS advisory locks for the **entire activation**. The new package-private `Lease.inspectVerifiedWalOffActorTurn()` rechecks actor-thread exclusion, the runtime's current generation, the ODF1 metadata and the WAL SHA-256 before returning an immutable replay inspection. No guest DATA field becomes a credential.
2. The ODR1 caller supplies a **separately confined, preopened, writable sidecar FileChannel**. `ReadOnlyOperationEvidenceLedger.recordOrInspect` takes a nonblocking exclusive OS advisory lease on this descriptor; if the sidecar is empty, it writes exactly one canonical ODR1 snapshot and calls `force(true)`; if nonempty, it must byte-match the expected snapshot **exactly**. Partial tails, old generations, changed request digests, foreign identities and conflicting content are refused with no repair/truncation. File ownership transfers only after successful initialization.
3. The snapshot binds `tenant,isolate,shard`, ODF1 generation and predecessor, last WAL generation/sequence/length, exact WAL SHA-256 and an ordered record of all observed operation IDs, sequences, kinds and canonical request hashes. The sidecar is bounded by the existing **4,096-record, 1MiB WAL** inspection; a frame request digest is SHA-256 over `kind (u8), keyLength (LE u16), valueLength (LE u16), key bytes, value bytes`. The source WAL validator independently rejects duplicate operation IDs.
4. Every reconciliation call happens **off a serialized actor carrier**; while the retained WAL/ODF1/ODR1 OS leases are valid it rereads the full bounded WAL, verifies ODF1 and checks the entire immutable ODR1 contents against the expected bytes. A failed authorization, invalid WAL or altered/torn sidecar quarantines **both** the evidence handle and the parent runtime activation; restoring bytes cannot revive either. A merely off-actor-turn violation is refused without DoS/poisoning healthy storage custody.
5. Reconciliation has only three outcomes: **`MATCHING_OPERATION_IN_VERIFIED_WAL`**, **`CONFLICTING_OPERATION_ID_IN_VERIFIED_WAL`**, and **`NOT_OBSERVED_OUTCOME_UNKNOWN`**. A matching record **does not prove the client received an ACK**. A missing ID **does not permit retry**, as the operation may exist in an earlier WAL incarnation or uncertain write. `canAcknowledgeGuest()` and `canResumeWrites()` are permanently false.
6. This **single-snapshot-per-generation** ODR1 format intentionally rejects a previous-generation sidecar after a new ODF1 generation is recorded. The next generation must use a separately preauthorized fresh sidecar and replay historical ODB1 operations. No compaction, continuation, repair, journal roll-forward or ledger rotation is authorized by this stage.

## ODR1 v1 fixed layout (provisional)

The file has a **120-byte** header followed by **N × 44-byte** records, where N is 0..4096. All integers are little-endian:

| Offset | Length | Field |
|---|---:|---|
| 0 | 4 | ASCII `ODR1` |
| 4 | 4 | version `1`; three reserved zero bytes |
| 8 | 12 | positive tenant, isolate and shard IDs |
| 20 | 28 | ODF1 generation, previous generation, WAL generation, final sequence, WAL byte length, record count, entry width = 44 |
| 48 | 32 | SHA-256 of full verified ODB1 file |
| 80 | 32 | SHA-256 of concatenated ordered 44-byte entries |
| 112 | 4 | CRC32C of header bytes 0..111 |
| 116 | 4 | LE marker `EDR1` |

Each entry comprises `sequence:i32, operation_id:i32, kind:u8, reserved:3×zero, request_sha256:32`. All file bytes, not just checksums, are rederived from an independently parsed ODB1+ODF1 snapshot on every read. CRC, SHA and advisory locks do **not** authenticate a malicious writer in control of storage, and the format does not prove true power-loss durability.

## Executed verification

Actual exact GitHub source blobs, no synthetic stand-ins, locally compiled together under **OpenJDK 21.0.12.1 `javac -Xlint:all -Werror`**:

| Suite | Local clean JVM runs | Checks | Result |
|---|---:|---:|---|
| New ODR1 evidence/reconciliation | 15 | 49/run = 735 | PASS |
| Existing Stage18 lifetime custody | 5 | 40/run = 200 | PASS |
| Existing Stage17 generation fence | 5 | 46/run = 230 | PASS |
| **Combined** | **25** | **1,165** | **PASS** |

The ODR1 tests use genuine OS files and independent Java child processes. They test matching PUT/DELETE, conflicting operation ID by key/value/kind, missing operation as UNKNOWN, bounded canonical sidecar size, same-inode/independent-JVM file-lock contention and release, off-carrier refusal, immutable ODF/WAL snapshots, same-size ODR1 sidecar corruption and later restoration, quarantining the parent runtime lease, changed checksum-valid WAL, zero-operation snapshot, torn sidecar with no repair, and a forked JVM that `force(true)`s ODR1 before `Runtime.halt(0)`. On restart an old-generation ODR1 is refused, not promoted to proof of ACK.

**Process termination after force is not a simulated power outage.** There is no established directory fsync or filesystem rollback resistance, no real Java/Graal isolate authorization, and no proof that client result delivery was atomic with WAL or metadata. All tests are local; the SHA-pinned GitHub workflow must execute actual steps to provide hosted CI evidence.

## Release blockers

- Stage18's source-org GitHub Actions still fail before runner allocation. The new `.github/workflows/db-odr1-operation-evidence.yml` pins eight exact Git blobs, checks HEAD and compiles/runs actual Java 21 sources for five rounds.
- Cross-organization private-source mirroring remains **blocked** by independent non-author transfer authorization in [issue #15](https://github.com/ores-truffle-oreslang/oreslang-db/issues/15). A successful alternate-org **source-free** runner canary does not authorize a copy.
- Before enabling a recovered writer or any committed client ACK: implement a real runtime-issued, path-confined FD capability; provenance across crash generations; safe append-at-recovered-end with idempotency and monotone sequence, timeouts and UNKNOWN reconciliation; operation-response delivery semantics; atomic manifest publication with file and directory fsync; and deterministic power-loss/fault-injection tests. Then compile the actual `.ores` actor and compare Java/Graal with zero-GC LLVM.

**Keep DRAFT; do not merge or release.**
