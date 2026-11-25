# Stage 18 — host-only lifetime WAL/ODF1 lease custody

**Draft scope:** This extends stage17 ODF1 candidate fencing with a host-only runtime custody object that holds **three exclusive OS advisory FileLocks** for the entire activation lifetime, rather than acquiring and releasing them around each ODF1 inspection. This explicitly does **NOT** implement recovered WAL append resumption, real actor-runtime authority, guest ACK, power-loss durability, or LLVM parity. All APIs remain package-private and must not be exposed to Oreslang guest DATA or polyglot interop.

## Contract

- `DbShardLifetimeCustody.fromTrustedRuntime(TrustedRuntime)` accepts a host runtime adapter contract. That interface does not authenticate itself. A genuine Graal/native backend must independently verify tenant/isolate/shard identity, ownership and confinement of three preopened file descriptors, scheduler/carrier identity and lifecycle. No caller-supplied path is accepted.
- `fenceAndHold` **fails on serialized actor turns**, acquires nonblocking exclusive file locks on WAL, ODF1 A and ODF1 B in stable order and validates the actual full bounded WAL and candidate index. Missing, foreign, torn or inconsistent metadata and stale generations fail closed. It writes the **provisional ODF1** generation candidate via stage17 `recordWithHeldLeases` while the SAME three locks remain held, performs `force(true)`, reads the record back and validates the WAL again. All three locks and FDs remain exclusively owned by the new host lease after return.
- The existing stage17 `inspect` and `recordCandidate` methods still acquire short-lived locks for host diagnostics, but now delegate to `inspectWithHeldLeases` and `recordWithHeldLeases`. The new overloads verify `lock.channel() == provided descriptor`, that locks are valid/nonshared, and that each descriptor is live. This avoids reentrant Java locks, unlocked handoff gaps and accidentally supplied unrelated locks. ODF1 bytes and on-disk format are unchanged.
- `Lease.verifyOffActorTurn()` re-inspects WAL and both ODF1 slots under the STILL-HELD leases and runtime `isCurrent` identity check; the verified SHA-256, operation digest, generation and counter must match the initially admitted candidate exactly. If runtime freshness fails, locks disappear, metadata changes, a valid same-length WAL changes or inspection fails, the handle permanently revokes before returning any observation. Diagnostics use cloned SHA bytes.
- `Lease.close()` rejects calls from actor turns, revokes FIRST, releases all three locks and closes all three descriptors. A failed shutdown quarantines the in-process slot rather than admitting a new generation. After success the owner cannot read again; competing processes can acquire the locks.
- **A failure after acquiring all locks** permanently quarantines the registry slot in that process, because an ODF1 write may have partially persisted. A pre-lock competing owner rejection does not burn the generation. Failed admission otherwise leaves FD ownership with the caller (locks released, descriptors not closed). A new process must still validate the on-disk slots before any new fence.
- `Lease.canResumeWrites()` and `Lease.canAcknowledgeGuest()` are **false**. This lease object is a local custody/diagnostic boundary; it is not an unforgeable authentication credential, a WAL writer, an operation-result ledger, or durable commit evidence.

## Actual local test evidence

Exact GitHub SHA-pinned six-source compilation with **OpenJDK 21.0.12.1, javac -Xlint:all -Werror**. No synthetic stand-ins:
- `ReadOnlyWalReplay.java`: `8da48ec37b5a2ab8cb141c2403669fce6ffb6024`
- `ReadOnlyShardRecovery.java`: `5208e8503587eb69aeb908a62bfd223b32961583`
- `ReadOnlyGenerationFenceLedger.java` (refactored): `3713fd611c8f5c7594aae5b2eb1b3317833c6efc`
- `DbShardLifetimeCustody.java` (new): `9cef4616352ac6a24cd1db0d0cc9b6a6aa2616ab`
- `ReadOnlyGenerationFenceLedgerTest.java` (unchanged): `f739bb4d8535492bb33a291ae6b039ba39fb5482`
- `DbShardLifetimeCustodyTest.java` (new): `d3eb543448e1408b7408d62ca7eb3a5976a3cbe5`

Strictly compiled once, then **10 independent Java invocations of each suite**, 40 new host-custody assertions and 46 inherited ODF1 assertions per invocation: **400 + 460 = 860/860 PASS**. The new tests use real temporary files and separate child JVMs. They cover denied runtime credential; blocking actor-turn rejection; off-turn admission/verification/closure; THREE held leases; an independent Java process failing to acquire file locks during custody but succeeding after closure; same-process ODF1 callers rejected while the lease is held; in-memory and persisted generation rollback rejection; A/B slot counter continuity through fresh registry instances; stale authority irrevocable poison; same-size checksum-valid WAL tampering and non-resurrection; fail-closed torn ODF1 slots; and safe preadmission rejection for a competing OS lease.

The unchanged ODF1 suite additionally includes process force-then-halt and recovery inspection. **Process halt is not a power-loss/fdatasync/parent-directory-fsync durability proof**. All `TrustedRuntime` adapters in tests are host-simulated and cannot prove genuine Oreslang actor-runtime isolation.

## Hosted CI and private source boundary

`.github/workflows/db-lifetime-custody.yml` checks the exact PR head and six Git blobs, compiles Java21 in strict mode and requires both suite outputs 5 times. The inherited `db-odf1-generation-fence.yml` source hash was also updated to the newly refactored ODF1 class, preserving the original tests. A workflow with zero executed steps is **NOT a passing source test**.

Source-org GitHub-hosted Actions have repeatedly failed before runner assignment. Independently approved private source transfer **is required** before copying any DB blobs to another org. [Issue #15](https://github.com/ores-truffle-oreslang/oreslang-db/issues/15) is the exact-tree security gate; an alternate org's successful source-free JDK probe does not authorize private mirroring or prove remaining minutes. No private source transferred or merged as part of this stage.

## P0 follow-up

1. Execute actual stage14 seven-file WAL writer/actor-turn production suite and stage18 six-file custody suite on admitted, authorized runners with nonzero steps.
2. Implement runtime-issued, authenticated actor activation tokens and FD custody, and ensure no blocking I/O on actor carriers. Retain lease for the full actor lifetime and handle fault/cancel/quiescence.
3. Introduce a **persisted operation-result ledger** and explicit UNKNOWN reconciliation; prove duplicate write retry cannot issue conflicting client outcomes after process loss.
4. Implement safe append-to-nonempty-WAL at the verified ODB1 offset and next sequence; never overwrite or reset the log; inject crashes around force, generation fencing and publication.
5. Prove real Java/Graal .ores DbShardActor compile/runtime integration and LLVM byte-for-byte parity before exposing committed guest ACKs.

**Keep DRAFT. No writer/guest ACK/release claims.**
