# ODB1 guest execution: verified backend gaps and graduation gates (2026-11-01)

**Scope:** this document audits executable status of the shared ODB1 `.ores` guest corpus from PRs #30/#31/#34. It is **not** a 5-backend execution receipt and grants no database, security, durable-ACK, actor, or compiler-parity claim.

## Shared guest requirements

The source in `src/wal/frame.ores` and its positive/negative guest tests require, at minimum:
- imported `wal_frame` modules and exported names;
- `class Frame`, `List<int>` allocation/index/mutation and `Result<T,String>` / `Ok` / `Err`;
- loops, branches, helper functions, comparisons, exact signed 32-bit byte operations (including `>> 8`), and bounded Adler-style A/B checksum limbs;
- guest `stdio.stdout.write`, with **exact, newline-free string output** for the 15-case parity runner;
- compiler entrypoints implementing `--check <path>` and `<path>` directly, **or** separately vetted per-backend CLI adapters that implement this exact contract, with source/launch provenance.

The native parity driver requires independent, SHA-256-pinned compiler executables and a clean, pinned Git checkout for each backend. It rejects missing/duplicate binaries and must run *all* native guests, not a source-free shell mock.

## Current backend capability audit

| Backend | Source repo documented capability | Blocker for this ODB1 guest | Needed next gate |
| --- | --- | --- | --- |
| Java/GraalVM | Reference implementation with nominal classes, actor support, package/stdlib APIs | No complete exact-head 15-fixture native guest receipt supplied to this PR | Independently built compiler with pinned SHA and exact `--check` / run output for every ODB1 case |
| LLVM native | Tested but explicitly incomplete source-to-LLVM/AOT backend | Complete ODB1 corpus and guest I/O behavior not demonstrated | Native full-source compile/run with ABI, stdout and checksum golden bytes matched |
| WASM | Bridge invokes **external LLVM compiler**; accepted core-Wasm ABI is **pure `() -> i64`**, zero imports; no guest host I/O | `stdio.stdout.write` and the current full guest API cannot run using this ABI; the bridge is **not an independently implemented Oreslang compiler** | Explicit portable result-buffer ABI or restricted stdout-free codec contract, with real wasm32 artifact verification and separate provenance of LLVM compiler |
| Cranelift | `main` with 0–4 `int` parameters; checked signed-i64 constants and limited arithmetic. Explicitly rejects imports, helper calls, classes, loops and control flow | Cannot parse `wal_frame` module, `Frame`, `List<int>`, `Result` or test loops | Implement/test the required frontend/IR/runtime features independently, or restrict shared parity to a **separately labeled scalar subset** |
| .NET Native AOT | Native AOT CIL compiler bootstrap for one checked-i64 arithmetic function. Explicitly no variables, imports, general functions or actors | Cannot compile ODB1 guest classes/collections/helper functions | Extend source parser, typed IR and Native AOT runtime; prove exact native executable, not merely generated CIL |

Sources: `oreslang-source.java/README.md`, `oreslang-source.llvm/README.md`, `oreslang-source.wasm/README.md`, `oreslang-source.cranelift/README.md`, `oreslang-source.net/README.md`, inspected at their respective **main** trees on 2026-11-01. These are backend authors' capability declarations; a README is not an executed CI receipt.

## Stage-34 metamorphic test hardening

The native guest gate now performs three experiments using **the same randomized source pathname**:
1. The unmodified `frame-roundtrip.ores` guest must pass both `--check` and execution with **exact** `db:wal-roundtrip-ok` bytes.
2. The same guest has only its final stdout literal changed to a random `db:wal-morph-...` string. Both checks must pass and execution must produce the **changed literal** exactly. An output-stub that always prints the original string fails.
3. The source is further corrupted by an impossible syntax fragment. Both check and run must reject with a normal nonzero status in 1–123. Timeout 124, launch errors 125–127, signal/crash statuses 128+, and 0 all fail closed. On return, temporary source/output files are removed.

These tests are necessary but insufficient: even a metamorphic challenge is not a cryptographic proof that the executable is a genuine compiler or that the backend's artifacts implement the complete Oreslang language.

**Local tests**, not native guest executions: `sh -n` and `ci/test-native-wal-negative-challenge.sh` produced **11 contract checks per execution, 20 repeated local executions = 220/220 passed mock-only checks**. Exact blob SHA:
- `ci/verify-native-wal-negative-challenge.sh`: `de2fc243ccb7ae80fee0d7628c3c021381e7c6f6`
- `ci/test-native-wal-negative-challenge.sh`: `a793d67d735e37f4b180d3ef29431e270edb7b55`

## Promotion and implementation order

1. **Java native**: build and execute real ODB1 corpus; capture compilation diagnostics for each fixture and fix genuine language type/ownership bugs.
2. **LLVM**: compile the same source, compare exact stdout and ODB1 raw bytes; do not substitute host-produced golden vectors.
3. **WASM**: define a zero-import, scalar/result-buffer ABI appropriate for ODB1, then compile through a separately attested LLVM frontend and execute actual wasm32 artifact. Do not count a Node mock.
4. **Cranelift / .NET**: add modules, control flow, list/index, discriminated result and core I/O semantics in distinct backend PRs with real native fixtures before claiming full ODB1 code compatibility. Until then, **explicitly unsupported**.
5. **End-to-end DB**: separately verify WAL owner capability, fsync, checkpoint directory sync, process/power fault boundaries, durable operation reconciliation, and actor-turn publication. These are not proved by any codec fixture.

Any PR/source-org CI job with `runner_id=null` and `steps=[]` provides **zero executable evidence**. The private-source cross-org transfer approval remains tracked by [issue #15](https://github.com/ores-truffle-oreslang/oreslang-db/issues/15); a successful source-free alternate runner does not authorize transfer of OresDB files.
