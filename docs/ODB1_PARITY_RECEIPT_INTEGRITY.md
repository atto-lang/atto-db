# ODB1 five-backend receipt integrity hardening

**Scope:** this patch hardens the **verification harness**, not Oreslang guest implementations, backend compiler binaries, WAL durability, recovered append, or guest ACK. The prior coordinator performed SHA and source-root preflight but credited `BACKEND_GUEST_CODEC_PASS` immediately after a child `verify-native-wal.sh` exited zero. A truncated or early-success child output could receive false positive credit.

## Boundaries

- `ci/assert-native-wal-receipts.sh` requires the supplied exact OresDB **commit**, **tree**, and **individual compiler binary SHA-256** to appear exactly once; the exact 15 guest fixture paths must appear in the **ordered** `FIXTURE_SHA256` and `GUEST_EXECUTED_PASS` lines. It rejects missing, repeated, reordered, malformed or extra success lines; requires exactly one matching terminal `GUEST_WAL_CODEC_ONLY_PASS cases=15`; and caps output at 128 KiB. Any trailing lines after terminal success are denied.
- `ci/verify-five-backend-wal-parity.sh` now captures each native child stdout and validates it **before** assigning any per-backend credit. It preflights physical source-root **uniqueness**, in addition to previously required independently pinned compiler paths/digests and exact git roots/commit/tree. Failures still refuse global five-backend credit.
- `ci/test-native-wal-receipts.sh` supplies **synthetic mock logs** to exercise 16 explicit adversarial rejects and one accepted valid-shape receipt. These tests DO NOT invoke a compiler or prove guest behavior. The existing `ci/test-five-backend-preflight.sh` uses an explicit mock child with 15 well-formed receipts, adds source checkout reuse rejection, and verifies that a summary-only child exit zero cannot gain backend credit.
- The host-only GitHub workflow invokes both shell tests and POSIX syntax preflight. **If Actions has zero runner steps, hosted verification remains unexecuted.**

### Executed local evidence

The new receipt helper and its exact GitHub-matching test file, GNU awk and `/bin/sh`, were executed **five rounds**, each yielding `HOST_NATIVE_GUEST_RECEIPT_REJECTION_PASS negative_cases=16; MOCK LOGS ONLY` (**80/80 malformed-case rejections**, plus one correctly shaped synthetic success each round). Both files' local `git hash-object` values match GitHub's returned blob SHAs:

- `ci/assert-native-wal-receipts.sh`: `466a9bdfcd3076e59165cf226e251e6696e04821`
- `ci/test-native-wal-receipts.sh`: `1972b9b8b0a32d3103eb594ddf05a7c5fc4a4dcb`

The integrated coordinator/workflow are committed but **not yet verified by a hosted runner**. Do not claim all 75 guest cells passed. The existing coordinator's mock test remains explicitly a **host-only test**.

### Parity and promotion

Every real Java, LLVM, WASM, Cranelift, and .NET backend cell must separately supply an executable compiled from independently verified source/commit/tree, SHA-256 pin, clean source checkout, `--check` and actual guest execution of the 15 unchanged `.ores` fixtures with verified receipts and stdout. The coordinator is not a runtime attestation scheme by itself: even correctly shaped textual receipts can be manufactured by a malicious adapter. Trusted artifact provenance and native execution are separate release gates.

The OresDB source organization has repeatedly failed Actions **before runner assignment**. The alternate private `zed-pkg/zed-docs` runner has passed a source-free probe, but [security issue #15](https://github.com/ores-truffle-oreslang/oreslang-db/issues/15) lacks independent cross-org private-source transfer approval. Do not copy any OresDB Git blobs to another organization without scope-pinned independent reviewer authorization. Keep this patch draft until live exact-head CI proves it and source/guest parity remains separately gated.
