# Stage 13 — host shard activation registry, generation fence and FD lifetime gate

This is a **draft host-only integration foundation**, not a production Oreslang DB and not a guest-visible storage authority or durable ACK. It is stacked on stage12 PR #20. A runtime must supply a genuine actor activation/custody verifier: passing a permissive fake is suitable only for isolated host tests.

## Implementation

`DbShardActivationRegistry` is package-private; it never accepts a filesystem path or a guest DATA identifier as a credential. Only a `TrustedRuntime` adapter may authorize a fresh activation and prove the current actor turn. For each `(tenant,isolate,shard)` within this JVM process:

- IDs and generation are positive. Admission requires a **preopened, empty** FileChannel and a positive trusted-runtime custody decision; this stage deliberately rejects restart/recovered/nonempty WAL.
- At most one active activation for each shard key. Generation must be strictly greater than all previously used generations in this registry. A second channel with the **same Java FileChannel object identity** is rejected across shard keys; distinct FDs pointing to the same inode are NOT detected. The runtime needs an independent OS-level exclusive lease and path/symlink confinement.
- The registry calls the existing package-private `WalRuntimeGrant` issuance and `ShardActorHandoffBridge`. The resulting `Activation` encloses Context, grant and the continuation handoff, and publishes no guest-constructible context or file descriptor.
- Calls to `appendOnActorTurn`, `getOnActorTurn`, and `snapshotOnActorTurn` must originate from the runtime-verified serialized actor turn. A cross-thread forged call is rejected without poisoning an otherwise current shard; a false or throwing **generation freshness** check poisons the activation permanently. Handoff notices must re-enter the runtime mailbox and revalidate activation and actor-turn status.
- `retireAsync` immediately fences future operations and invokes the existing asynchronous grant revoke + close. Replacement is prohibited until the original WAL disk/handoff tasks finish and the FileChannel closes. If closure fails, the shard key is quarantined indefinitely in this registry instance. No early reuse, blind retry or guest ACK.
- `canAcknowledgeGuest()` is permanently false. Neither a synced host receipt nor a host-local index publication constitutes a guest database commit.

**Critical limits:** this is an *in-process* epoch table. It does not authenticate a tenant against a real isolate runtime, prevent separately opened FDs from referencing the same file, pin an OS lock, persist an epoch across a crash, verify a completed recovery scan, or implement a true Oreslang scheduler. The `TrustedRuntime` interface itself must not be implemented by a guest actor, sourced from DATA, or callable from untrusted polyglot interop. Never publish it as a guest API.

## Test evidence

- **Local host smoke:** OpenJDK21 `javac -Xlint:all -Werror`, exact GitHub-source blob hashes `384c08c7d9eae93d620f557edd267079f25f22b4` for registry and `b7f3bc8cc44b368fe39b3fa34e17c6e68462b2bc` for test. 23 assertions × 5 rounds passed, using **synthetic package-local WAL/grant/bridge stand-ins**, not the real production sources. The synthetic harness wrote and called `FileChannel.force(true)` on real temp files. Do **not** count 115 local assertions as a production integration pass, backend parity, actor compiler pass, or power-loss durability proof.
- The new `.github/workflows/db-shard-activation-registry.yml` is **the unexecuted production gate**: exact GitHub PR head SHA, seven immutable production + test blob hashes, `javac -Xlint:all -Werror` Java21, five runs of the test against actual `WalFilePrimitive`, `WalRuntimeGrant`, `WalActorContinuationGate`, `ShardPublicationGate`, and `ShardActorHandoffBridge`, byte-exact expected stdout. A run with `steps=[]` supplies **zero** production evidence.
- The test's `TrustedRuntime` uses a single-thread virtual executor with an explicit ThreadLocal turn flag. This validates the host callback contract, **not** the Graal isolate or real Oreslang actor scheduler.

## Next promotion gates

1. Run exact-source seven-file Java production integration; inspect executed steps, artifacts, failures and actual process exit codes. Source-org runner admission remains blocked; external source mirroring needs separate independent source-transfer approval for this exact HEAD/tree under issue #15.
2. Wire the real Java/Graal actor scheduler's current-turn and generation tokens; verify a poisoned actor fails shut on late callbacks, failure, cancellation, shutdown and isolate retirement. This registry's in-process generation counter is not a durable fence.
3. Build an OS-level independently authenticated shard file lease (openat2/descriptor-relative confinement, lock/lifetime pins, no symlinks/guest paths); prove no distinct-FD inode aliases across leases.
4. Implement a compiled native `DbShardActor` guest message protocol with sealed state, typed get/put/delete, no blocking/suspension in critical sections and runtime-only continuation delivery.
5. Rebuild persistent operation-ID ledger and index from authenticated ODB1 replay, reconcile UNKNOWN after process death, and implement atomic checkpoint image validation with file fsync and directory fsync. Run deterministic crash injection and JVM/LLVM differential fixtures.

No SQL, MVCC, replication, durable guest ACK or LLVM runtime implementation is claimed. Keep **DRAFT** until these gates are met.
