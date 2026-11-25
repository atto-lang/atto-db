# Stage 16 — runtime-bound, read-only WAL recovery admission

**DRAFT, HOST ONLY.** No recovered writer, no mutable actor state, no committed guest ACK, no persistent generation ledger, no Java/Graal or LLVM runtime integration. This stage adds a bounded, read-only recovery **admission handle** on top of the genuine stage15 ODB1 scanner and candidate index. Its security identity comes only from a **separately trusted backend runtime**; the Java `TrustedRuntime` interface is a host adapter contract, NOT authentication by itself.

## Admission and lifetime

- The runtime provides an independently vetted `ShardKey(tenant,isolate,shard)`, positive **proposed** next generation, and a confined preopened **writable descriptor**. The admission never accepts guest-supplied paths, opens paths, writes, truncates, repairs or silently retries the WAL. The caller owns the descriptor if admission fails; ownership transfers to the admission only on success.
- The adapter's `mayInspect` must authenticate the tenant and file provenance. The registry rejects a second active handle, an in-process stale generation, or a previously failed FD closure, then acquires a **nonblocking exclusive** `FileChannel.tryLock` before reading. Same-process overlapping inode handles and competing processes are refused. Failure after acquiring the lease releases it without consuming the generation.
- Under that lock, **real** `ReadOnlyShardRecovery.inspectPreopened` validates the complete ODB1 stream and bounded materialized index. The proposed generation must strictly exceed the highest generation inside the inspected WAL. Its local registry also checks monotonically increasing epochs but **does not persist them across restart**. An empty WAL is inspectable but remains read-only.
- The internal `ReadOnlyShardRecovery.Candidate` and live `FileChannel` remain private and are never exposed as a guest return value. `Admission.diagnostics()` returns only value-type metadata (last sequence, verified byte offset, proposed/observed generations, slot/byte counts and a copied SHA-256). `candidateGet()` clones values; `operationEvidence()` distinguishes **observed in THIS WAL** vs **not observed in THIS WAL**. Missing operation IDs NEVER mean it is safe to retry.
- Every observational read requires runtime `isCurrent(admission)` and re-verifies the **entire bounded WAL** under its retained lease against the admitted SHA-256 and original size. A lost FD lease, revoked generation, invalid WAL or changed same-size checksum-valid content poisons the admission **irreversibly**. A recovery-read failure never promotes partial candidate data into actor state.
- `close()` revokes the handle before releasing the advisory OS lock and closing the transferred FD. Failed closure permanently quarantines the shard slot in this registry instance. Calls on a closed or stale handle cannot read the candidate, even if the source bytes are restored.
- `canResumeWrites()` and `canAcknowledgeGuest()` are always **false**. The verified offset and SHA256 are observations, **not** writer resumption authority.

## Proven tests — five actual Java sources, zero synthetic stand-ins

OpenJDK **21.0.12.1**, `javac -Xlint:all -Werror`. All files below were compiled and executed **together** from exact GitHub blob bytes:

| File | Git blob SHA |
| --- | --- |
| `ReadOnlyWalReplay.java` | `8da48ec37b5a2ab8cb141c2403669fce6ffb6024` |
| `ReadOnlyShardRecovery.java` | `5208e8503587eb69aeb908a62bfd223b32961583` |
| `DbShardRecoveryAdmission.java` | `633ae45460f31f1a7285eac8c0cd1e8053756a35` |
| `ReadOnlyShardRecoveryTest.java` | `9e7f0109fd94dc1a80d4a81689aa7e070b659193` |
| `DbShardRecoveryAdmissionTest.java` | `4109c3aff5420e3bc4bd6bdc1dac5c914792e782` |

- Stage16: **48 checks × 20 local clean subprocess runs = 960/960 PASS**. Runtime-denied/foreign identity, rejected epoch > WAL generation, exclusive inode lock and second runtime registry rejection, candidate PUT/DELETE/operation-ID/defensive SHA, retirement and new epoch, corrupted checksum failure without orphan lock, revocation across temporary runtime auth restoration, changed WAL with bad checksum and **same-sized checksum-valid WAL with changed SHA**, irrevocable quarantine, and forked **independent Java process** holding the OS advisory WAL lock before admission.
- Re-run unchanged Stage15 suite against the same compiled classes: **44 checks × 10 runs = 440/440 PASS**. Includes separate child process `FileChannel.force(true)` followed by `Runtime.halt(0)`, parent read-only replay under a new lock; no power-loss or checkpoint durability claim.
- Combined observed local regression assertions: **1,400/1,400 PASS**. Child subprocesses use actual temporary OS files and Java kernel file locks. This suite simulates a trusted runtime adapter via a strict Java identity/reference check; it does **not** execute an Oreslang actor runtime.

The new `.github/workflows/db-recovery-admission.yml` pins the **five exact** GitHub blob SHAs, checks the PR head, compiles JDK21 with strict lint, and requires byte-exact output for **5 rounds of both** actual-source suites. A hosted workflow with `runner_id=null, steps=[]` supplies **zero** production execution evidence.

## Security limitations and next gates

This code cannot prove the adapter came from a real runtime or that a preopened FD is safely confined. Java's OS lock is advisory, not protection from non-cooperating writers; same-size changes between checksum checks are possible absent independent custody. Repeated full-WAL SHA rescans per read are diagnostic and bounded to the existing 1 MiB WAL scanner, not a production query-path design. A different JVM process starts with a new in-memory generation ledger; therefore the proposed generation is **not** a persistent anti-replay guarantee.

1. Execute **stage14 seven-real-class** WAL writer/activation/actor-handoff tests and **stage16 five-class** readonly admission tests on a GitHub runner with actual nonzero steps. Source-org workflows have repeatedly failed before allocation. An alternate private org's source-free canary alone does not approve copying private files; [issue #15](https://github.com/ores-truffle-oreslang/oreslang-db/issues/15) requires an independent non-author review of the exact private-source SHA subset, destination collaborators, artifact/log retention and cleanup.
2. Introduce a **durably persisted** cross-restart generation/custody ledger and independently verified WAL append offset/operation-ID reconciliation. Do not enable a writer on a nonempty WAL until crash-injection proves no overwrite, duplicate operation, stale activation or unsafe ACK.
3. Connect runtime issuance to a genuine Java/Graal isolate and serialized `.ores` DbShardActor turn. Prove failure-mode quarantining and compare ODB1 golden fixtures against LLVM zero-GC native implementation.

Keep **DRAFT**; no merge/release, private cross-org copying, native guest execution or durable database commit is claimed.
