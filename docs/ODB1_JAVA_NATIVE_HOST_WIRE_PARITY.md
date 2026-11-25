# ODB1 wire compatibility: Java production replay vs independent native C++ reader

**Evidence scope:** host runtime, test-only native executable. This is **not** Oreslang guest-language execution, Java/GraalVM native-image admission, production LLVM backend integration, WAL write authority, durability proof or committed guest acknowledgment.

Existing OresDB `ReadOnlyWalReplay.java` is the actual production Java host-only read-only WAL parser. Its source is retained *unchanged* and must match Git blob `8da48ec37b5a2ab8cb141c2403669fce6ffb6024`. The independent native C++ implementation is placed under `runtime/llvm/test/` and is compiled into a **test executable** with Clang and GCC; it does not claim to be linked into `oreslang-source.llvm`.

Run `bash ci/verify-odb1-java-llvm-host-interop.sh` to compile strict Java 21 (`-Xlint:all -Werror`) and strict ISO C++20 (`-Wall -Wextra -Wconversion -Werror -pedantic`, optimized) under separate Clang and GCC toolchains. The script uses a temporary directory and no environment secrets, network access, or cross-organization checkouts.

Each of the **two independent encoders** emits five byte-for-byte identical WAL files: empty, one PUT, PUT+DELETE with increasing generation/sequence, signed-31-bit extreme identities with binary key/value bytes, and maximal 64-byte key/1024-byte value. The script verifies all bytes with `cmp`.

The **native reader** and the real **Java production parser** independently execute against the five Java-created and five C++-created files, 19 adversarial native-generated files (including one intentionally checksum-valid Adler-32 payload collision), and 32 single-byte mutations. They must return identical status and diagnostic text. The test also asserts precise expected diagnostics for malformed magic/version/kind, high-bit u32 identities, duplicate operation IDs, gaps/regressions, tenant mismatch, deletion-with-value, invalid lengths, checksum corruption, torn/partial WAL tails, >4096 records and >1 MiB.

The script tests foreign tenant rejection independently and repeats native replay across separate OS processes. **136 checks** cover two separate host C++ compilers. An additional local Clang AddressSanitizer/UndefinedBehaviorSanitizer pass exercised valid, invalid and mutant WAL files with no sanitizer findings; the CI workflow currently runs optimized builds only.

Native parsing intentionally does not replace the production Java runtime's trusted preopened-descriptor custody or its double file-size checks. The C++ test tool uses local test file paths and does not acquire durable write authority or guarantee isolation from malicious concurrent filesystem operations. Both implementations reject ambiguous corruption but an Adler checksum is not authentication.

**Native guest parity remains open:** no independently pinned `oreslang-source.java` or `.llvm` compiler artifact was available for local execution; no actual `.ores` guest was compiled or executed. WASM, Cranelift and .NET full guest support is also unverified. Do not count these host checks toward the 16-fixture Oreslang guest parity matrix or merge/release based on them alone. Alternate-org private source transfer remains subject to independent approval in OresDB issue #15.
