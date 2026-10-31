package dev.oreslang.db;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;

/** Host-side JDBC reference. Not a guest API, native async driver, or PostgreSQL wire pool. */
public final class ActorPgPool implements AutoCloseable {
    public record Template(String id, String sql, int parameters, boolean readOnly) {
        public Template {
            Objects.requireNonNull(id);
            Objects.requireNonNull(sql);
            if (!id.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || parameters < 0 || parameters > 64 ||
                sql.isBlank() || sql.indexOf(';') >= 0 || sql.indexOf('\0') >= 0)
                throw new IllegalArgumentException("invalid registered SQL template");
        }
    }
    public record Bytes(byte[] value) {
        public Bytes { value = Objects.requireNonNull(value).clone(); }
        @Override public byte[] value() { return value.clone(); }
    }
    public record Outcome(int updateCount, List<List<Object>> rows) { }

    /**
     * Trusted-host-only database login binding. This object and its DataSource
     * must never be handed to an Oreslang actor or serialized as DATA.
     */
    public record PrincipalBinding(String databaseRole, DataSource source) {
        public PrincipalBinding {
            Objects.requireNonNull(databaseRole);
            Objects.requireNonNull(source);
            if (!databaseRole.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))
                throw new IllegalArgumentException("invalid PostgreSQL principal identifier");
        }
    }

    /** Trusted-host-issued opaque capability, never a DATA message. */
    public static final class Capability {
        private final ActorPgPool owner;
        private final String tenant;
        private final Set<String> allowed;
        private volatile boolean revoked;
        private Capability(ActorPgPool owner, String tenant, Set<String> allowed) {
            this.owner = owner;
            this.tenant = tenant;
            this.allowed = allowed;
        }
    }
    private final class Submission extends CompletableFuture<Outcome> {
        private Job job;
        /** Cancellation is definitive only while queued. Dispatch may already have side effects. */
        @Override public boolean cancel(boolean interrupt) {
            synchronized (gate) {
                ArrayDeque<Job> queue = pending.get(job.capability.tenant);
                if (queue == null || !queue.remove(job)) {
                    // JDBC work has been claimed by an owner (or already completed).
                    // Do not report successful cancellation of an uncertain write.
                    return false;
                }
                if (queue.isEmpty()) {
                    pending.remove(job.capability.tenant);
                    ready.removeIf(job.capability.tenant::equals);
                }
                release(job.capability.tenant);
            }
            // CompletableFuture user callbacks must not run while holding the pool gate.
            return super.cancel(false);
        }
    }
    private record Job(Capability capability, Template template, List<Object> arguments, Submission completion) { }
    private record Completion(Job job, Outcome outcome, Exception error) { }

    private final DataSource source;
    private final Map<String, PrincipalBinding> principals;
    private final Map<String, Template> templates;
    private final int maxTotal, maxTenant, maxRows, maxResultBytes;
    private final Object gate = new Object();
    private final Map<String, ArrayDeque<Job>> pending = new HashMap<>();
    private final Map<String, Integer> outstandingByTenant = new HashMap<>();
    private final ArrayDeque<String> ready = new ArrayDeque<>();
    private final List<Thread> workers = new ArrayList<>();
    private final BlockingQueue<Completion> completions;
    private final List<Thread> completionWorkers = new ArrayList<>();
    private int outstanding;
    private boolean closed;

    /** Legacy single-role host reference; a tenant GUC is not an authorization principal. */
    public ActorPgPool(DataSource source, Collection<Template> registered, int connections,
                       int maxTotal, int maxTenant, int maxRows, int maxResultBytes) {
        this(Objects.requireNonNull(source), Map.of(), registered,
             connections, maxTotal, maxTenant, maxRows, maxResultBytes);
    }

    /**
     * Credential-fenced host reference: each tenant is bound to a distinct,
     * non-superuser PostgreSQL login role through a trusted-host DataSource.
     * Workers retire a connection before switching tenants, and independently
     * verify session_user/current_user against the expected principal.
     */
    public ActorPgPool(Map<String, PrincipalBinding> principals, Collection<Template> registered,
                       int connections, int maxTotal, int maxTenant,
                       int maxRows, int maxResultBytes) {
        this(null, validatePrincipals(principals), registered,
             connections, maxTotal, maxTenant, maxRows, maxResultBytes);
    }

    private static Map<String, PrincipalBinding> validatePrincipals(Map<String, PrincipalBinding> configured) {
        Objects.requireNonNull(configured);
        if (configured.isEmpty()) throw new IllegalArgumentException("no tenant principals");
        Map<String, PrincipalBinding> verified = new HashMap<>();
        Set<String> roles = new HashSet<>();
        for (var entry : configured.entrySet()) {
            String tenant = entry.getKey();
            PrincipalBinding binding = Objects.requireNonNull(entry.getValue());
            if (tenant == null || tenant.isBlank() || tenant.length() > 256 ||
                tenant.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("invalid principal tenant");
            if (!roles.add(binding.databaseRole()))
                throw new IllegalArgumentException("database principal shared across tenants");
            verified.put(tenant, binding);
        }
        return Map.copyOf(verified);
    }

    private ActorPgPool(DataSource source, Map<String, PrincipalBinding> principals,
                        Collection<Template> registered, int connections,
                        int maxTotal, int maxTenant, int maxRows, int maxResultBytes) {
        this.source = source;
        this.principals = principals;
        if (connections < 1 || connections > 128 || maxTotal < 1 || maxTenant < 1 ||
            maxTenant > maxTotal || maxRows < 1 || maxResultBytes < 1)
            throw new IllegalArgumentException("invalid pool limits");
        this.completions = new ArrayBlockingQueue<>(maxTotal);
        this.maxTotal = maxTotal;
        this.maxTenant = maxTenant;
        this.maxRows = maxRows;
        this.maxResultBytes = maxResultBytes;
        Map<String, Template> entries = new HashMap<>();
        for (Template t : Objects.requireNonNull(registered)) {
            if (entries.putIfAbsent(t.id(), t) != null) throw new IllegalArgumentException("duplicate template");
        }
        this.templates = Map.copyOf(entries);
        for (int i = 0; i < Math.min(4, Math.max(2, connections)); i++) {
            Thread completionWorker = new Thread(this::dispatchCompletion, "ores-pg-completion-" + i);
            completionWorker.setDaemon(true);
            completionWorkers.add(completionWorker);
            completionWorker.start();
        }
        for (int i = 0; i < connections; i++) {
            Thread worker = new Thread(this::work, "ores-pg-jdbc-owner-" + i);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
    }

    public Capability grantTenant(String tenant, Set<String> allowedIds) {
        if (tenant == null || tenant.isBlank() || tenant.length() > 256 ||
            tenant.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid tenant");
        if (!principals.isEmpty() && !principals.containsKey(tenant))
            throw new SecurityException("tenant has no authorized database principal");
        Set<String> ids = Set.copyOf(allowedIds);
        if (ids.isEmpty() || !templates.keySet().containsAll(ids))
            throw new IllegalArgumentException("unregistered permission");
        synchronized (gate) {
            if (closed) throw new IllegalStateException("pool closed");
            return new Capability(this, tenant, ids);
        }
    }

    /** Revocation removes queued operations; already executing SQL cannot be interrupted safely. */
    public void revoke(Capability capability) {
        Objects.requireNonNull(capability);
        if (capability.owner != this) throw new SecurityException("foreign database capability");
        List<Job> rejected = new ArrayList<>();
        synchronized (gate) {
            if (capability.revoked) return;
            capability.revoked = true;
            ArrayDeque<Job> queue = pending.get(capability.tenant);
            if (queue != null) {
                for (Iterator<Job> it = queue.iterator(); it.hasNext();) {
                    Job job = it.next();
                    if (job.capability == capability) { it.remove(); rejected.add(job); }
                }
                if (queue.isEmpty()) {
                    pending.remove(capability.tenant);
                    ready.removeIf(capability.tenant::equals);
                }
            }
        }
        for (Job job : rejected) {
            try { completions.put(new Completion(job, null, new SecurityException("database capability revoked"))); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                synchronized (gate) { release(job.capability.tenant); }
                job.completion.completeExceptionally(e);
            }
        }
    }

    public CompletableFuture<Outcome> submit(Capability capability, String id, List<?> arguments) {
        Objects.requireNonNull(capability);
        Objects.requireNonNull(id);
        Objects.requireNonNull(arguments);
        if (capability.owner != this || capability.revoked || !capability.allowed.contains(id))
            throw new SecurityException("database capability denied");
        Template t = templates.get(id);
        if (t == null || arguments.size() != t.parameters())
            throw new IllegalArgumentException("unknown template or parameter count");
        List<Object> snapshot = new ArrayList<>(arguments.size());
        long encodedBytes = 0;
        for (Object v : arguments) {
            Object copied = ActorPgValues.copyValue(v);
            encodedBytes += ActorPgValues.estimatedArgumentBytes(copied);
            if (encodedBytes > 1024L * 1024L)
                throw new IllegalArgumentException("aggregate database argument budget exceeded");
            snapshot.add(copied);
        }
        Submission promise = new Submission();
        Job job = new Job(capability, t, Collections.unmodifiableList(snapshot), promise);
        promise.job = job;
        synchronized (gate) {
            if (closed) throw new IllegalStateException("pool closed");
            if (capability.revoked) throw new SecurityException("database capability revoked");
            if (outstanding >= maxTotal || outstandingByTenant.getOrDefault(capability.tenant, 0) >= maxTenant)
                throw new IllegalStateException("database admission backpressure");
            ArrayDeque<Job> queue = pending.computeIfAbsent(capability.tenant, k -> new ArrayDeque<>());
            if (queue.isEmpty()) ready.addLast(capability.tenant);
            queue.addLast(job);
            outstanding++;
            outstandingByTenant.merge(capability.tenant, 1, Integer::sum);
            gate.notifyAll();
        }
        return promise;
    }

    private void release(String tenant) {
        outstanding--;
        int next = outstandingByTenant.get(tenant) - 1;
        if (next == 0) outstandingByTenant.remove(tenant);
        else outstandingByTenant.put(tenant, next);
    }
    private Job next() throws InterruptedException {
        synchronized (gate) {
            while (true) {
                while (!ready.isEmpty()) {
                    String tenant = ready.removeFirst();
                    ArrayDeque<Job> queue = pending.get(tenant);
                    if (queue == null || queue.isEmpty()) { pending.remove(tenant); continue; }
                    Job job = queue.removeFirst();
                    if (!queue.isEmpty()) ready.addLast(tenant);
                    else pending.remove(tenant);
                    return job;
                }
                if (closed) return null;
                gate.wait();
            }
        }
    }
    private static void verifyDatabasePrincipal(Connection connection, String expectedRole)
            throws SQLException {
        // These are SQL server values, NOT JDBC metadata or caller-supplied GUCs.
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT session_user, current_user");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || !expectedRole.equals(rows.getString(1)) ||
                !expectedRole.equals(rows.getString(2)) || rows.next())
                throw new SQLException("authenticated PostgreSQL principal mismatch");
        }
    }

    private void work() {
        Connection connection = null;
        String connectionTenant = null;
        try {
            while (true) {
                Job job = next();
                if (job == null) break;
                Outcome result = null;
                Exception error = null;
                try {
                    if (!job.completion.isCancelled()) {
                        if (!principals.isEmpty()) {
                            String tenant = job.capability.tenant;
                            if (connection != null && !tenant.equals(connectionTenant)) {
                                // Closing before switching forbids reuse of a session
                                // authenticated as another tenant.
                                closeQuietly(connection);
                                connection = null;
                                connectionTenant = null;
                            }
                            PrincipalBinding binding = principals.get(tenant);
                            if (binding == null) throw new SecurityException("unbound database tenant");
                            if (connection == null) {
                                connection = binding.source().getConnection();
                                connectionTenant = tenant;
                            }
                            // Every reuse is checked in case an allowlisted host
                            // template or DataSource poisoned the role.
                            verifyDatabasePrincipal(connection, binding.databaseRole());
                        } else if (connection == null) {
                            connection = source.getConnection();
                        }
                        result = JdbcOperation.execute(connection, job.template,
                                job.capability.tenant, job.arguments, maxRows, maxResultBytes);
                    }
                } catch (Exception failure) {
                    error = failure;
                    closeQuietly(connection);
                    connection = null;
                    connectionTenant = null;
                }
                // No actor callback on a JDBC connection owner; bounded handoff.
                completions.put(new Completion(job, result, error));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(connection);
        }
    }
    private void dispatchCompletion() {
        try {
            while (true) {
                Completion completed = completions.poll(100, TimeUnit.MILLISECONDS);
                if (completed == null) {
                    synchronized (gate) {
                        if (closed && outstanding == 0 && completions.isEmpty()) break;
                    }
                    continue;
                }
                synchronized (gate) { release(completed.job.capability.tenant); }
                if (!completed.job.completion.isCancelled()) {
                    // Effects can already have committed. Never deliver stale results
                    // to a capability revoked while its database work was in flight.
                    Exception failure = completed.error;
                    if (completed.job.capability.revoked || closed)
                        failure = new IllegalStateException("operation owner revoked or pool closed; database outcome may be committed");
                    if (failure == null) completed.job.completion.complete(completed.outcome);
                    else completed.job.completion.completeExceptionally(failure);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    private static void closeQuietly(Connection connection) {
        if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
    }
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (Thread thread : workers) {
            if (Thread.currentThread() == thread) return false;
            long nanos = deadline - System.nanoTime();
            if (nanos <= 0) return false;
            thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
            if (thread.isAlive()) return false;
        }
        for (Thread thread : completionWorkers) {
            if (Thread.currentThread() == thread) return false;
            long nanos = deadline - System.nanoTime();
            if (nanos <= 0) return false;
            thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
            if (thread.isAlive()) return false;
        }
        return true;
    }
    @Override public void close() {
        List<Job> rejected = new ArrayList<>();
        synchronized (gate) {
            if (closed) return;
            closed = true;
            for (ArrayDeque<Job> queue : pending.values()) rejected.addAll(queue);
            pending.clear();
            ready.clear();
            gate.notifyAll();
        }
        for (Job job : rejected) {
            try { completions.put(new Completion(job, null, new IllegalStateException("pool closed"))); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                synchronized (gate) { release(job.capability.tenant); }
                job.completion.completeExceptionally(e);
            }
        }
    }
}
