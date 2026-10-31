package dev.oreslang.db;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/** Simulated JDBC fault oracles only; not a live PostgreSQL or guest test. */
public final class JdbcRollbackSafetyTest {
    private JdbcRollbackSafetyTest() {}

    private enum Failure { NONE, EXECUTE, COMMIT, ROLLBACK, COMMIT_AND_ROLLBACK }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static final class FakeConnection {
        final Failure failure;
        int starts;
        int implicitCommitResets;
        int commits;
        int rollbacks;
        int contextCalls;
        int statementCalls;

        FakeConnection(Failure failure) { this.failure = failure; }

        Connection connection() {
            return proxy(Connection.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "setReadOnly", "close" -> { return null; }
                    case "setAutoCommit" -> {
                        if ((boolean) args[0]) implicitCommitResets++;
                        else starts++;
                        return null;
                    }
                    case "prepareStatement" -> {
                        String sql = (String) args[0];
                        boolean context = sql.startsWith("SELECT set_config");
                        return statement(context);
                    }
                    case "commit" -> {
                        commits++;
                        if (failure == Failure.COMMIT || failure == Failure.COMMIT_AND_ROLLBACK)
                            throw new SQLException("injected COMMIT failure");
                        return null;
                    }
                    case "rollback" -> {
                        rollbacks++;
                        if (failure == Failure.ROLLBACK || failure == Failure.COMMIT_AND_ROLLBACK)
                            throw new SQLException("injected ROLLBACK failure");
                        return null;
                    }
                    default -> throw new AssertionError("unexpected Connection call: " + method.getName());
                }
            });
        }

        PreparedStatement statement(boolean context) {
            return proxy(PreparedStatement.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "setString", "setObject", "setBytes", "setQueryTimeout", "close" -> {
                        return null;
                    }
                    case "execute" -> {
                        if (context) {
                            contextCalls++;
                            return true;
                        }
                        statementCalls++;
                        if (failure == Failure.EXECUTE || failure == Failure.ROLLBACK)
                            throw new SQLException("injected query failure");
                        return false;
                    }
                    case "getUpdateCount" -> { return 1; }
                    default -> throw new AssertionError("unexpected PreparedStatement call: " + method.getName());
                }
            });
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void scenario(Failure failure, boolean expectedSuccess) throws Exception {
        FakeConnection fake = new FakeConnection(failure);
        ActorPgPool.Template template = new ActorPgPool.Template(
            "write", "UPDATE actor_state SET version = version + 1", 0, false
        );
        SQLException rejected = null;
        try {
            ActorPgPool.Outcome result = JdbcOperation.execute(
                fake.connection(), template, "tenant-a", List.of(), 10, 65536
            );
            check(expectedSuccess, "failed transaction incorrectly returned success");
            check(result.updateCount() == 1, "missing success update count");
        } catch (SQLException failureObserved) {
            rejected = failureObserved;
            check(!expectedSuccess, "healthy transaction unexpectedly rejected");
        }
        check(fake.starts == 1, "transaction never began");
        check(fake.contextCalls == 1 && fake.statementCalls == 1, "missing transaction-local context or SQL");
        if (expectedSuccess) {
            check(fake.commits == 1 && fake.rollbacks == 0, "healthy transaction completion");
            check(fake.implicitCommitResets == 1, "healthy connection not returned to autocommit");
        } else {
            check(fake.implicitCommitResets == 0, "cleanup may implicitly COMMIT uncertain transaction");
            check(fake.rollbacks == 1, "error did not attempt rollback");
            if (failure == Failure.COMMIT_AND_ROLLBACK)
                check(rejected != null && rejected.getSuppressed().length == 1,
                      "rollback failure was not preserved");
        }
    }

    public static void main(String[] args) throws Exception {
        scenario(Failure.NONE, true);
        scenario(Failure.EXECUTE, false);
        scenario(Failure.ROLLBACK, false);
        scenario(Failure.COMMIT, false);
        scenario(Failure.COMMIT_AND_ROLLBACK, false);
        System.out.println("ACTOR_PG_ROLLBACK_NO_IMPLICIT_COMMIT_HOST_PASS");
    }
}
