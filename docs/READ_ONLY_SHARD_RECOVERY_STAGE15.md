# Stage 15 — bounded read-only ODB1 shard reconstruction (HOST candidate only)

This slice is **not a recovered live Oreslang shard**, not a writable WAL resumption adapter, not an authenticated actor storage capability, and not a durable client ACK. It builds a diagnostic candidate on the **actual inherited** `ReadOnlyWalReplay.java` source, without changing that scanner's ODB1 framing or validation contract.

## Design

`ReadOnlyShardRecovery.inspectPreopened(FileChannel, trustedTenant)` requires a trusted, preopened descriptor held under an **independently authenticated exclusive lease**. The caller, not this class, owns the lease, FD lifetime, tenant identity and actor generation. No user paths or guest DATA fields grant storage access. The method never writes, repairs, truncates, resumes, or closes a WAL.

1. The inherited `ReadOnlyWalReplay.inspectPreopened` rejects incomplete or corrupt frames, unknown types, foreign tenant, bad checksum, sequence gap, nonmonotone generation, repeated operation ID, excessive record count and excessive file size. No repair/truncate authority is issued for a partial tail.
2. Stage15 materializes a **candidate** map for complete PUT and DELETE records, preserving distinct **lifetime** key slots across tombstones (maximum 128) and bounding retained live value bytes (maximum 131,072). Each observed operation ID is retained in an in-memory diagnostic history of at most 4,096 records, already bounded by the WAL scanner.
3. Materialization is all-or-nothing. A log exceeding the lifetime key-slot or live-byte budget yields `InvalidIndex` with an exact offending sequence and does not expose an inconsistent partial map. Input and returned value bytes and the SHA-256 fingerprint are copied defensively.
4. After materializing the candidate, the code invokes the exact source scanner **again** and checks length/fingerprint equality, to detect changed WAL bytes during the read/build cycle. This mitigates some same-size concurrent mutation but **cannot** guarantee stability without trusted exclusive custody, prevent malicious ABA changes or authenticate the producer.
5. `Candidate.observedOperationInWal(op)` means **only** that a valid operation with that ID occurs in the inspected file. Its absence is **not** permission to retry or a proof of noncommit: an unknown operation may have been written to a previous incarnation, be in an unverified segment, or have reached a caller before a failure. This candidate has `canAcknowledgeGuest() == false` and `canResumeWrites() == false` unconditionally.

There is **no activation registry attachment**, no automatic actor-state promotion, no persistent generation grant, no checkpoint image verification, and no Java/LLVM guest parity claim.

## Local verification — exact three production/test Git blobs

- `ReadOnlyWalReplay.java`: **`8da48ec37b5a2ab8cb141c2403669fce6ffb6024`** (inherited unchanged; GitHub and local `git hash-object` exact match).
- `ReadOnlyShardRecovery.java`: **`5208e8503587eb69aeb908a62bfd223b32961583`**.
- `ReadOnlyShardRecoveryTest.java`: **`9e7f0109fd94dc1a80d4a81689aa7e070b659193`**.

On local OpenJDK **21.0.12.1**, `javac -Xlint:all -Werror` compiled all **three actual byte-exact sources together**, with **no synthetic WAL source or grant stand-ins**. A Java test subprocess executed **20 successive clean iterations × 44 assertions = 880/880 PASS**, with byte-exact stdout:

`HOST_READ_ONLY_SHARD_RECOVERY_PASS checks=44 (full WAL candidate only; NOT durable ACK or resumed writer)`

Tests include empty WAL, generational PUT/overwrite/DELETE, tombstone reinsert, operation-ID observations, duplicate ID, foreign tenant, corrupted checksum, short/torn tail (no writes), 129th lifetime key-slot rejection, exact SHA-256, defensive getters, positional FileChannel cursor invariance, real exclusive OS advisory locks on inspected files, and a **forked Java writer** that holds its OS lease, writes a two-record WAL, `FileChannel.force(true)`, calls `Runtime.halt(0)`, and is replayed by a separate parent process after acquiring the lock. This is a **process-termination/recovery observation**, not power-loss durability or directory `fsync`/checkpoint proof.

## CI and promotion controls

The SHA-pinned `.github/workflows/db-readonly-shard-recovery.yml` checks the exact PR head, three Git source hashes, strict Temurin21 compilation, and five real-file/forked-JVM regressions. If GitHub Actions fails **before runner allocation** with `steps=[]`, do not claim it executed. Source-org runner admission has repeatedly failed in this PR stack.

[Issue #15](https://github.com/ores-truffle-oreslang/oreslang-db/issues/15) requires separate independent approval before **cross-organization private-source mirroring**. The narrowest alternate-org candidate for this stage contains only three immutable Java source/test blobs plus reviewed no-secrets workflow. An alternate org's successful source-free canary is neither proof of an available future minutes budget nor consent to disclose private DB source; do not copy without approval.

## Next genuine runtime work

- Execute previous stage14 **seven** production-class Java tests with nonzero runner steps.
- Bind candidate replay to an authenticated lease and activation generation, but **do not permit writes** until a persistent epoch ledger, replay-to-writer transition and sealed actor publication contract are separately implemented.
- Implement operation-ID reconciliation across process death, checkpoint generation and canonical on-disk index fingerprint, including actual file fsync, atomic rename and parent-directory fsync.
- Compile `DbShardActor` guest source with real Java/Graal and LLVM runtimes; prove actor-turn nonreentrancy and ownership parity before guest ACK.

**Keep DRAFT. No merge/release, source transfer or durable DB claim.**
