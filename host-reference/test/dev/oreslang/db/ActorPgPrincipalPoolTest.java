package dev.oreslang.db;

import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * No PostgreSQL dependency: exact actor-owner lifecycle, physical DB role
 * fencing, principal mismatch and poisoned-role reuse host-only oracles.
 */
public final class ActorPgPrincipalPoolTest {
    private ActorPgPrincipalPoolTest() {}

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> api, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(api.getClassLoader(),
                new Class<?>[] {api}, handler);
    }
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    static final class RoleSource implements DataSource {
        final String loginRole;
        final AtomicReference<String> activeRole;
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger statements = new AtomicInteger();
        final AtomicInteger checks = new AtomicInteger();

        RoleSource(String loginRole) {
            this.loginRole = loginRole;
            this.activeRole = new AtomicReference<>(loginRole);
        }
        @Override public Connection getConnection() {
            opens.incrementAndGet();
            return proxy(Connection.class, (conn, method, args) -> {
                switch (method.getName()) {
                    case "setReadOnly", "setAutoCommit", "commit", "rollback" -> { return null; }
                    case "close" -> { closes.incrementAndGet(); return null; }
                    case "prepareStatement" -> {
                        String sql = (String) args[0];
                        if (sql.equals("SELECT session_user, current_user")) {
                            checks.incrementAndGet();
                            return proxy(PreparedStatement.class, (stmt, action, values) -> {
                                switch (action.getName()) {
                                    case "executeQuery" -> {
                                        AtomicInteger cursor = new AtomicInteger();
                                        return proxy(ResultSet.class, (rows, op, parameters) -> {
                                            switch (op.getName()) {
                                                case "next" -> { return cursor.getAndIncrement() == 0; }
                                                case "getString" -> {
                                                    return ((Integer) parameters[0]) == 1
                                                            ? loginRole : activeRole.get();
                                                }
                                                case "close" -> { return null; }
                                                default -> throw new AssertionError("unexpected principal ResultSet call: " + op.getName());
                                            }
                                        });
                                    }
                                    case "close" -> { return null; }
                                    default -> throw new AssertionError("unexpected principal statement call: " + action.getName());
                                }
                            });
                        }
                        return proxy(PreparedStatement.class, (stmt, op, values) -> {
                            switch (op.getName()) {
                                case "setQueryTimeout", "setString", "setObject", "setBytes", "close" -> {
                                    return null;
                                }
                                case "execute" -> {
                                    if (!sql.startsWith("SELECT set_config")) statements.incrementAndGet();
                                    return false;
                                }
                                case "getUpdateCount" -> { return 1; }
                                default -> throw new AssertionError("unexpected SQL call: " + op.getName());
                            }
                        });
                    }
                    default -> throw new AssertionError("unexpected connection call: " + method.getName());
                }
            });
        }
        @Override public Connection getConnection(String u, String p) throws SQLException {
            throw new SQLException("host must not supply guest credentials");
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter writer) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getAnonymousLogger(); }
        @Override public <T> T unwrap(Class<T> api) throws SQLException { throw new SQLException("unwrap denied"); }
        @Override public boolean isWrapperFor(Class<?> api) { return false; }
    }
    static ActorPgPool newPool(Map<String, ActorPgPool.PrincipalBinding> principals) {
        return new ActorPgPool(principals,
            List.of(new ActorPgPool.Template("write", "UPDATE actor_state SET version=version+1", 0, false)),
            1, 8, 8, 4, 65536);
    }
    static void await(ActorPgPool pool) throws InterruptedException {
        pool.close();
        check(pool.awaitTermination(5, TimeUnit.SECONDS), "owner/completion threads not retired");
    }
    static void write(ActorPgPool pool, ActorPgPool.Capability capability) throws Exception {
        check(pool.submit(capability, "write", List.of()).get(5, TimeUnit.SECONDS).updateCount() == 1,
              "prepared UPDATE did not complete");
    }
    public static void main(String[] args) throws Exception {
        RoleSource a = new RoleSource("db_tenant_a");
        RoleSource b = new RoleSource("db_tenant_b");
        Map<String, ActorPgPool.PrincipalBinding> map = Map.of(
            "tenant-a", new ActorPgPool.PrincipalBinding("db_tenant_a", a),
            "tenant-b", new ActorPgPool.PrincipalBinding("db_tenant_b", b)
        );
        try (ActorPgPool pool = newPool(map)) {
            var capA = pool.grantTenant("tenant-a", Set.of("write"));
            var capB = pool.grantTenant("tenant-b", Set.of("write"));
            try {
                pool.grantTenant("tenant-c", Set.of("write"));
                throw new AssertionError("unbound tenant capability admitted");
            } catch (SecurityException expected) { }
            write(pool, capA);
            write(pool, capA);
            check(a.opens.get() == 1 && a.checks.get() == 2,
                  "same-principal reuse was not identity checked");
            write(pool, capB);
            check(a.closes.get() == 1 && b.opens.get() == 1,
                  "cross-tenant connection switch reused physical session");
            write(pool, capA);
            check(b.closes.get() == 1 && a.opens.get() == 2,
                  "tenant switching failed to re-authenticate");
            a.activeRole.set("db_tenant_b");
            try {
                pool.submit(capA, "write", List.of()).get(5, TimeUnit.SECONDS);
                throw new AssertionError("poisoned current_user accepted");
            } catch (ExecutionException expected) {
                check(expected.getCause().getMessage().contains("principal mismatch"),
                      "principal mismatch was not surfaced");
            }
            check(a.statements.get() == 3, "poisoned identity executed SQL");
            check(a.closes.get() == 2, "poisoned connection was not retired");
            a.activeRole.set("db_tenant_a");
            write(pool, capA);
            check(a.opens.get() == 3, "poisoned session reused after failure");
            await(pool);
        }
        check(a.closes.get() == a.opens.get() && b.closes.get() == b.opens.get(),
              "principal-bound physical connection leaked");
        try {
            newPool(Map.of(
                "tenant-a", new ActorPgPool.PrincipalBinding("duplicate_role", a),
                "tenant-b", new ActorPgPool.PrincipalBinding("duplicate_role", b)
            ));
            throw new AssertionError("shared database login accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            new ActorPgPool.PrincipalBinding("role;SET ROLE other", a);
            throw new AssertionError("unsafe database identifier admitted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("ACTOR_PG_PRINCIPAL_FENCED_OWNER_HOST_PASS");
    }
}
