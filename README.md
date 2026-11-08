# oreslang-db

**Status: design-only bootstrap.** This repository contains no Oreslang DB driver, query engine, compiled guest tests, or production database implementation yet.

## Integration contract

- Oreslang owns its DB-facing typed API and actor-confined connection/session ownership; database engine and wire protocol are adapters, not compiler intrinsics.
- Proposed upstreams: [Oreslang serialization and validation](https://github.com/ores-truffle-oreslang/oreslang-serialization-and-validation) for bounded codecs and schema refinements; [Java reference compiler](https://github.com/ores-truffle-oreslang/oreslang-source.java) for guest language/ownership semantics.
- Prepared statements and typed positional parameters must not interpolate untrusted data as SQL code.
- Transactions have explicit begin/commit/rollback states; cancellation/restart must close ownership and pending resources exactly once.
- Privileged capabilities and connections cannot be sent as ordinary DATA messages. Connection credentials are never logged or included in test fixtures.

## First executable milestone

1. Define Oreslang-native `DbError`, `DbValue`, `DbSession`, and bounded row-stream contracts under `src/`.
2. Add a deterministic fake-provider test only as **host/contract evidence**, clearly separated from actual guest compiled execution.
3. Pin a reference compiler commit; compile and execute a small prepared-select plus failure/rollback guest fixture, with positive and negative oracles.
4. Run real backend integration tests only against isolated disposable databases, never production credentials.

Tracked by the private [organization integration registry PR #79](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/79). No private source mirroring without independent approval.

## Repository ownership and migration (2026-11-08)

This source repository remains `ores-truffle-oreslang/oreslang-db` until an authorized transfer. The public language name is **AttoLang**; existing `.ores` source and `oreslang-*` packages, repos and CLI names are compatibility contracts, not automatically renamed.

- **Belongs here:** AttoLang database engine primitives, WAL/storage experiments, actor-state ownership and recovery tests.
- **Must live elsewhere:** commercial managed DB hosting, tenant account administration or cloud billing.
- **Organization boundary:** [atto-lang](https://github.com/atto-lang) owns language/compiler/runtime specifications, portable libraries, CLI and testable demos; [atto-co](https://github.com/atto-co) separately manages hosted cloud infrastructure, deployed customer services, tenancy, identity, billing and production operations. An existing `atto-*` repository is not evidence of this repo's transferred identity or tested implementation parity.
- **First-step migration proposal:** `atto-lang/oreslang-db` via a real GitHub ownership transfer preserving old name, full history and numeric repository ID. **No direct legacy-org → atto-co transfer is authorized** in this wave; any later managed-product carve-out requires independent review.

**Preflight:** coordinate in [issue #26](https://github.com/ores-truffle-oreslang/.github/issues/26); consult the private [migration PR #25](https://github.com/ores-truffle-oreslang/.github/pull/25). Record exact source HEAD SHA/tree and stable ID, source/target owner and independent security signoff, target permissions/protected rulesets, PRs, tags/releases, packages, Apps, secrets, webhooks, Pages, Actions/runner admission, external consumers and rollback owner. No private source mirrors as shortcuts. Only after the authorized transfer validate unchanged ID/history/tree, rebind security checks, then update references and run executable exact-head CI. `steps=[]`/`null` means `NOT_EXECUTED`; structural host-only evidence does not certify guest execution or production networking/database behavior.
