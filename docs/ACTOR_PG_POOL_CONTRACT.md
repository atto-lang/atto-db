# Oreslang actor-shared PostgreSQL: security and performance contract

Status: **design contract plus Java 21 JDBC host-reference prototype on this draft PR**, not a production guest driver. Fake-DataSource tests are not PostgreSQL execution evidence; the disposable PostgreSQL 17 integration and native guest/backend execution remain unverified until runnable jobs produce exact-head evidence. No native async PostgreSQL protocol, AOT, LLVM, or GraalVM guest execution is claimed.

## Ownership and authority
- A runtime-owned pool belongs to exactly one database credential/role and trust domain.
- One protocol owner controls a physical TCP connection, transaction state, prepared statements and incoming results. No actor obtains a raw socket or JDBC connection.
- The host issues non-serializable, non-transferable-in-DATA capabilities bound to a tenant, role and allowlisted operation IDs. Untrusted actors cannot register arbitrary SQL or create capabilities.
- All operations use positional parameters, never guest-provided SQL snippets, identifiers or connection strings. Database security uses least-privileged roles and tenant-isolating RLS; the query planner is not an authorization barrier.
- Do not reuse connections across different database authentication roles. A transaction-local tenant setting must be set inside the transaction and must be backed by verified RLS; arbitrary SQL must not be able to replace this setting.
- No actor private-heap pointer, secret, TLS state or unvalidated result buffer may cross actors. Immutable output values are bounded and defensively copied. Logs and panic traces must redact parameter values and credentials.

## Scheduling
- Fixed physical connection budget; each controller drives one connection.
- Bounded global and per-tenant outstanding jobs **including executing work**, bounded rows and bytes, and fair admission across tenant queues. Treat backpressure as retryable without manufacturing a new durable operation ID.
- Suspend actor tasks rather than blocking actor scheduler worker threads. A JDBC reference may use separate blocking host threads; production async should use reactor-driven nonblocking I/O and pipeline only across explicit safe protocol synchronization points.
- Cancel queued jobs promptly; in-flight cancellation and connection retirement must be explicit. Never release capacity before the physical operation is complete.
- Never run guest callbacks or continuation resumption inline on the connection/protocol owner. Actor completion identity must include generation/activation checks to discard stale responses.

## Transactions and failure
- One-shot statements use a transaction-local tenant scope and rollback on error. A transaction obtains an exclusive bounded lease for its entire lifetime.
- Return a connection to the pool only once its protocol state and transaction boundaries are known clean; otherwise close and replace it. A JDBC reset of autocommit alone is insufficient for arbitrary session-modifying SQL.
- Do not automatically retry an ambiguous write or commit. A TCP error after sending a statement can leave an indeterminate database outcome. Persist an idempotency key or reconcile before retrying.
- Durable actor turns must atomically persist versioned state, inbox receipt and outbox intent under activation fencing; this is distinct from generic pooled access. Supervisor should coordinate recovery, not serialize every child's database operation.
- READ/WRITE transactions, session features (LISTEN, advisory locks, temp tables, prepared statement lifetime), PgBouncer modes, DDL and COPY need dedicated documented affinity rules.

## Threat and validation matrix
1. Two tenants concurrently querying through the same role must not see or mutate each other's rows; use PostgreSQL RLS with test roles.
2. Reject forged, revoked and overbroad capabilities, raw SQL bypass, oversized/untyped values and cross-isolate handles.
3. Backpressure must account for in-flight and queued work under concurrent cancellation; test fairness and no double-release.
4. Reject stale actor generations and resume tokens after actor restart, supervisor takeover and hot reload.
5. Inject connection failure before send, after send, during results, during COMMIT and during ROLLBACK. Verify no uncertain connection reuse or blind write retry.
6. Exercise pool closure, hung query/host timeout, callbacks that synchronously submit another query, and prevention of socket-owner deadlocks.
7. Confirm duplicate operation ID deduplication, fenced state persistence, and outbox reconciliation.
8. Run Java 21 host proof, real disposable PostgreSQL with enforced RLS, GraalVM guest integration, and LLVM backend conformance as **separate** gates. Only claim coverage actually executed.

## Source/CI integrity
A private source must never be checked out by another organization's GITHUB_TOKEN without explicit access. If source-org Actions cannot schedule work, mirror the exact reviewed Git tree into a temporary branch of a **private** test repository, pin and verify the source commit/tree/blob hashes, run tests using the test repository's own token, then destroy the mirror. Do not expose source in a public repo, copy production secrets, or claim passing CI when jobs fail before runner assignment.

The executable driver and guest-level proof remain follow-up work. Keep this contract scoped to optional database/stdlib; freestanding core must remain usable without OS or DB dependencies.
