# WAL runtime opening and atomic write bridge — stage 1.8

**Status:** host-only draft source, partial locally executed tests. The native Oreslang database actor is still reject-all. There is no guest-callable storage authority, commit ACK, full backend build, or process power-loss durability proof.

## Java/GraalVM: one-shot physical opening

Backend draft [Java PR #2362](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2362) source head `f3e7b3d4f4ced8cdb18e6a540959508b2fe1a7e2` now atomically transitions an authenticated host-issued shard grant from ACTIVE to OPENING **before** AsyncNativeIo admission. A second open reservation fails. Invalid WAL basenames/generation and scoped permission requests run before the reservation.

A never-started operation can remove OPENING only through AsyncNativeIo's trusted atomic never-started callback. For a worker that DID start, the grant records the exact returned `DbDurableWalFile` object before registration, and only a successful close of that same object may retire a failed open. A different closed file cannot serve as release proof. A thrown open with unknown OS-handle state or a file retained by context shutdown remains reserved/quarantined instead of enabling a competing owner.

`DbWalTrustedGrantRegistryTest` adds the opening reservation, exact physical-file identity and never-started/failed-open cases; `DbDurableWalFileTest` adds invalid-basename preflight coverage. These JUnit tests are authored **NOT executed** against the full compiler runtime. The current source's `DbDurableWalFile.java` Git blob SHA `1f7f56e499233f24c8c3d2d500089aba690b10e7` was independently reconstructed and verified via `git hash-object` and passed local Java 21 `--release 21 -Xlint:all -Werror` plus the existing read-only WAL recovery host smoke. This is narrower than a full exact-head build.

## LLVM: atomically authorized physical write

Backend draft [LLVM PR #1232](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/1232), source head `68d0ec9366ddb6774776c5551a3ac43534a9d8c9`, now offers a **synchronous host-only** `DbDurableWalFile::append_authorized_and_sync` bridge. It takes fstat device/inode from the actual opened physical file, atomically obtains `DbWalShardGrantRegistry::begin_write` against `drain`, validates a detached bounded ODB1 frame, physically appends and completes fdatasync while holding the gate, and retires the physical write slot on either success or error. This still does **not** integrate a source-language actor lifetime pin, issue a guest receipt, or authenticate the numeric issuer fields without the trusted runtime.

New tracked `native_db_wal_authorized_writer_test.cpp` covers wrong context/tenant/shard/actor, byte-corrupt wire, no file mutation on rejection, successful fdatasync, physical slot retirement, concurrent writer-versus-drain and final close/retirement. CTest name: `native_db_wal_authorized_writer_host_only`.

**Actually executed locally** under g++ C++17, `-Wall -Wextra -Wpedantic -Werror -UNDEBUG -pthread -fsanitize=address,undefined`, ASan leak detection on, UBSan halt-on-error: tracked test passed **80 independent sanitized race runs**. Git blob SHA verification was exact:
- `DbDurableWalFile.hpp`: `da18efd6b14acb8ac5677cf59a0eb25a7dbb4a40`
- `native_db_wal_authorized_writer_test.cpp`: `f31885831e037bc7861ff4f8fd1cd126f1cb442a`
- Existing `DbWalShardGrantRegistry.hpp`: `80ea864efef1d3d627ef1f35143e569e30279cff`

These are genuine isolated source-exact host tests, **not** a full source-tree CMake/CTest run, backend compiler output or guest actor proof.

## Runner and promotion boundary

The latest inspected exact-head source runs still failed to start: Java synthetic workflow [#41236314922](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41236314922) and LLVM compiler/native workflow [#41236160235](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/actions/runs/41236160235) both report `runner_id=0`, steps=0. Earlier source-free authorized private-carrier canaries also failed runner assignment; no private source was mirrored or sent to a public org.

Next gates: execute full Java JUnit and LLVM CMake/CTest on an actual runner; wire authenticated tenant/isolate/actor/gen grants and AsyncIoActorLifetimePin through the real compiler/runtime to `DbStorageActor`; only then implement runtime-verified sync receipts, staged replay/publication, parent-directory fsync, and `put -> ACK -> kill -> restart -> replay -> get`.

**All three PRs remain DRAFT; no merge or release.**
