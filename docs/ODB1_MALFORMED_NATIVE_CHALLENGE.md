# ODB1 native guest malformed-source challenge — five-backend parity evidence hardening

This source-only hardening is **not implementation or proof of Java, LLVM, WASM, Cranelift or .NET Oreslang compilation**. It rejects one false-positive mode in the existing [15-fixture ODB1 native guest parity runner](../ci/verify-native-wal.sh).

## The gap

The prior native runner checked an independently provided compiler binary hash and positive fixture stdout. Those checks are necessary but are not sufficient to prove that a launcher is a parser/compiler: a stub can emit hardcoded expected success strings based on fixture filenames. The five-backend coordinator's source-free regression uses deliberately mocked compiler binaries, and is therefore **host-only contract evidence**.

## New fail-closed native challenge

Before awarding any positive native guest pass, `verify-native-wal.sh` runs `ci/verify-native-wal-negative-challenge.sh`. The helper:

1. Requires the same absolute compiler executable and validates its independently pinned SHA-256 again. The parent runner has already verified the Git commit/tree and each backend's separate source checkout and executable digest.
2. Copies a known valid guest into a randomized temporary **same-directory** `.ores` path, retaining relative import behavior. **Before mutation, the very same copied source** must successfully pass `--check` and execute with exact `db:wal-roundtrip-ok` stdout. Next the tool changes just its success string to a random `db:wal-morph-...` value at that same path. Both check and run must succeed again, and stdout must equal the new value; a hardcoded-output wrapper fails. Blanket rejection of temporary paths cannot satisfy this gate.
3. Appends an impossible syntax fragment to that already-mutated file and invokes that same SHA-pinned compiler again in `--check` and normal execution modes. Both must reject with a normal nonzero status **1..123**. Acceptance (`0`), timeout (`124`), launcher errors (`125..127`) and signal/crash statuses (`128+`) are **failures** and confer no backend credit.
4. Deletes both the temporary source and output-control file through an exit/signal trap. Existing positive ODB1 fixtures continue to require exact check/execute output and all 15 case receipts.

This reduces false claims but cannot distinguish a genuine Oreslang parser from an advanced mock that intentionally rejects malformed syntax. **Actual native backend artifacts and runtime executions remain independently required.**

## Locally executed tests

`sh -n` validates both new POSIX shell scripts. The separately executed `ci/test-native-wal-negative-challenge.sh` used **mock compilers only** to test eleven host contracts over twenty clean runs, asserting the exact rejection message at each intended check rather than accepting any early error: accept-all, accepts check only, accepts run only, timeout-like, signal-like, blanket rejection of valid and invalid source, syntax-specific rejection, hardcoded unmodified guest output, rejection of only the valid metamorphic guest, bad binary digest and cleanup. Exact output:

`HOST_NATIVE_NEGATIVE_CHALLENGE_CONTRACT_PASS cases=11; MOCK COMPILERS ONLY; NO GUEST EXECUTION`

Two new Git blob SHA pins independently matched locally executed files:
- `ci/verify-native-wal-negative-challenge.sh`: `de2fc243ccb7ae80fee0d7628c3c021381e7c6f6`
- `ci/test-native-wal-negative-challenge.sh`: `a793d67d735e37f4b180d3ef29431e270edb7b55`

The PR updates the existing `db-parity-preflight.yml` to execute this new host suite and the syntax checks, and updates `verify-native-wal.sh` to enforce the challenge for **all five configured backend adapters**. The CI result must show a runner and nonzero executed steps; a zero-step workflow is an infrastructure failure, never evidence of a passing compiler.

## Remaining requirements

- Execute real, independent, SHA-pinned compiler and runtime artifacts for **java, llvm, wasm, cranelift, dotnet**. Do not treat the common `.ores` fixture source or host-only mock preflight as native parity.
- Verify backends individually and publish a matrix of artifact digest, source commit/tree, exact fixture stdout and terminal process exit codes. At least 15 original ODB1 guest cases and this negative canary must pass on every backend before a five-backend claim.
- ODB1 is still an **unauthenticated codec**. This PR adds no WAL writer, durable checkpoint, actor-owned runtime grant, native DB ACK or power-loss recovery claim.
- Private-source runner transfer remains gated by independent approval recorded in [issue #15](https://github.com/ores-truffle-oreslang/oreslang-db/issues/15). No source transfer is authorized by this document.

Keep this PR **DRAFT** until the source-org or approved alternate CI actually executes the relevant jobs.
