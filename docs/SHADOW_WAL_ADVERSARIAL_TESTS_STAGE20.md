# Additional stage20 shadow-WAL boundary regressions

The exact-head host suite now includes expanded **real JDK FileChannel** cases, with no guest ACK or live-writer promotion:
- Eight malformed requests: operation IDs zero/negative, kind zero/unknown, key length zero/65, value length 1025, and DELETE carrying a nonempty value. Each must be rejected before writing a physical shadow.
- FileLock contention from the same process: refuse an overlapping inode lock without clearing the incumbent lease or changing the shadow.
- Nonempty corrupt shadow refusal: preexisting three bytes remain untouched; no repair/truncate.
- Defensive SHA-256 copy: returned hash matches independent replay; mutating one returned copy cannot mutate the Probe.
- Valid DELETE operation has empty value, no key in recovered candidate index, no client ACK, and never changes the original source WAL.
- Additional Probe contract: null digest refused and defensive digest size/authority checks; output `checks=12`.

The integration source contains **34 syntactic `check(` sites** but also invokes the two common assertion helpers **10 times** (eight malformed requests plus two rejected shadow operations). Those helper invocations add 8 dynamic assertions beyond the literal-site count: **42 runtime checks per integration run**. New workflow compiles the exact SHA-pinned Java 21 source set and both tests, runs five complete separate Java invocations each, and compares the exact text outputs `checks=42` and `checks=12`. This describes **authored expectations, not executed outcomes**; after the branch push, GitHub Actions runner assignment and step metadata determine if any actual run happened.

Previous test sources remain included in the PR's ancestry. No native .ores guest, runtime-issued actor storage authority, production append-to-nonempty-WAL, durable ACK, OS power-loss proof, or LLVM parity. No cross-organization private-source mirroring absent independent security approval under issue #15. Keep draft.
