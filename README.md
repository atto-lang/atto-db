> **AI agent instructions (organization-wide):** Before working on code, issues, or pull requests, read and follow the [organization `AGENTS.md`](https://github.com/atto-lang/.github/blob/main/AGENTS.md), including its **merge-first stealth-development policy**. Honor any repository-local `AGENTS.md`, explicit holds, and GitHub-enforced rules.

# atto-db

Database access abstractions and persistence infrastructure for AttoLang.

## Naming migration and language authority

This repository is maintained at [atto-lang/atto-db](https://github.com/atto-lang/atto-db); use `https://github.com/atto-lang/atto-db.git` for Git remotes and canonical `.atto` source examples. The normative language contract is [atto-spec](https://github.com/atto-lang/atto-spec), with executable guest evidence tracked by [atto-platform-conformance](https://github.com/atto-lang/atto-platform-conformance). Follow current ownership, borrowing, effects, actor, and syntax rules there; do not infer implemented features from a README or unexecuted CI.

## Responsibilities

Portable database interfaces, transactions, connection lifecycles, and error reporting.

## Design guidelines

- Keep application-facing contracts separate from hosted Atto services.
- Explicitly specify error, cancellation, concurrency, and resource-lifecycle behavior where applicable.
- Document supported compiler backends, host restrictions, wire formats, and compatibility constraints.

## Development and verification

Use the source tree and CI for verified build/test commands. Tests should cover positive cases, invalid inputs, lifecycle behavior, and cross-backend interoperability where relevant.

## Status

This README describes the project's intended role. It does not imply that the listed APIs or platforms are already implemented or supported.

## Related

- [AttoLang](https://github.com/atto-lang)
- [Language specification](https://github.com/atto-lang/atto-spec)
- [Platform conformance](https://github.com/atto-lang/atto-platform-conformance)

## Repository ownership and contribution routing

**Belongs here:** Portable database contracts, connection/transaction abstractions, persistence interfaces and owned async resource lifetimes.

**Belongs elsewhere:** PostgreSQL-specific ORM mappings in atto-postgres-orm.atto; commercial hosted database service in atto-co; compiler I/O primitives in backend repos.

**Security and portability boundary:** Document WAL/recovery consistency, async cancellation, tenant isolation, quotas and credential handling; never leak borrowed buffers across actor boundaries.

AttoLang is the canonical project name; new source, examples and documentation use `.atto` and the `atto-lang` repository organization. This README defines scope, not feature readiness.

**Acceptance:** name exact source SHA/tree, runtime/toolchain, positive and negative checks and execution receipt before claiming support. Host-only tests and empty CI steps do not prove guest execution.

## Dependency migration planner (host reference only)

`reference/migration_plan.py` provides a deterministic, lexical-tie-break topological sort of migration IDs and dependency edges. It rejects cycles, unknown or duplicate dependencies, invalid names, and inconsistent already-applied histories. This reference **never** connects to a database, reads credentials, opens SQL files or applies a migration. No AttoLang or database-runtime support is claimed.

Run `python3 -m unittest discover -s tests -v` for host-only checks. Future implementations must add transactional locking, checksums, WAL/recovery behavior, actor-safe cancellation and per-backend execution evidence.

## Legacy compatibility and migration evidence

The archived [legacy Oreslang database repository](https://github.com/ores-truffle-oreslang/oreslang-db) is historical provenance, not the canonical development location. Existing `.ores` fixtures, package names and CLI names are legacy compatibility inputs; verify their syntax and behavior against the current Atto specification and actual compilers before claiming support. New canonical examples use `.atto`.

Database engine primitives, WAL/storage experiments, actor-owned recovery and portable database contracts belong here. The [atto-lang](https://github.com/atto-lang) organization owns language, compiler/runtime specifications and portable libraries; [atto-co](https://github.com/atto-co) separately owns hosted infrastructure, customer tenancy, identity, billing and production operations. Do not infer a GitHub ownership transfer or implementation parity merely from matching repository names.

For future repository transfers or source exports, record source and target numeric repository IDs, exact commit/tree, authorization, permissions, rulesets, PRs, releases, packages, Apps, secrets, webhooks, Actions admission, consumers and rollback ownership. Verify the resulting identity/history/tree and rebind security checks after an authorized transfer. Private-source CI mirrors require the applicable independent authorization and exact-tree verification; never copy credentials or use a source-organization token in a carrier. No transfer or private-source export is authorized by this document.

Use prepared statements and typed parameters, explicit commit/rollback and cancellation lifecycles, and actor-confined capabilities. Never log credentials or send privileged connection handles as ordinary DATA messages. Run database integration tests against disposable isolated databases, not production credentials. Distinguish host-reference tests from actual compiled guest execution and durable recovery evidence.
