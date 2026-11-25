package dev.oreslang.db;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

/** Host-only JDBC regression: implicit commit on a failed rollback is forbidden. */
public final class JdbcTransactionSafetyTest {
    private static final class Probe implements InvocationHandler {
        final boolean failStatement;
        final boolean failRollback;
        final boolean failCommit;
        int commits, rollbacks, setAutoCommitTrue;

        Probe(boolean failStatement, boolean failRollback, boolean failCommit) {
            this.failStatement = failStatement;
            this.failRollback = failRollback;
            this.failCommit = failCommit;
        }
        Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, this);
        }
        private PreparedStatement statement(boolean context) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "close", "setString", "setQueryTimeout", "setObject", "setBytes" -> { return null; }
                            case "execute" -> {
                                if (!context && failStatement) throw new SQLException("statement failed");
                                return context;
                            }
                            case "getUpdateCount" -> { return 1; }
                            default -> { throw new AssertionError("unexpected statement call: " + method.getName()); }
                        }
                    });
        }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
            switch (method.getName()) {
                case "setReadOnly", "close" -> { return null; }
                case "setAutoCommit" -> {
                    if ((boolean) args[0]) setAutoCommitTrue++;
                    return null;
                }
                case "prepareStatement" -> {
                    return statement(((String) args[0]).startsWith("SELECT set_config"));
                }
                case "rollback" -> {
                    rollbacks++;
                    if (failRollback) throw new SQLException("rollback failed");
                    return null;
                }
                case "commit" -> {
                    commits++;
                    if (failCommit) throw new SQLException("commit uncertain");
                    return null;
                }
                default -> throw new AssertionError("unexpected connection call: " + method.getName());
            }
        }
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
    public static void main(String[] args) throws Exception {
        var write = new ActorPgPool.Template("test", "UPDATE x SET value=1", 0, false);
        var failed = new Probe(true, true, false);
        try {
            JdbcOperation.execute(failed.connection(), write, "tenant-a", List.of(), 1, 1024);
            throw new AssertionError("failed statement accepted");
        } catch (SQLException expected) {
            check(expected.getMessage().contains("statement failed"), "original SQL failure preserved");
            check(expected.getSuppressed().length == 1, "rollback failure preserved");
        }
        check(failed.rollbacks == 1 && failed.commits == 0, "failed operation rolled back only");
        check(failed.setAutoCommitTrue == 0, "failed rollback must not implicitly commit");

        var uncertain = new Probe(false, false, true);
        try {
            JdbcOperation.execute(uncertain.connection(), write, "tenant-a", List.of(), 1, 1024);
            throw new AssertionError("uncertain commit accepted");
        } catch (SQLException expected) {
            check(expected.getMessage().contains("commit uncertain"), "commit uncertainty preserved");
        }
        check(uncertain.commits == 1 && uncertain.setAutoCommitTrue == 0,
                "ambiguous commit must not be followed by autocommit reset");

        var success = new Probe(false, false, false);
        var result = JdbcOperation.execute(success.connection(), write, "tenant-a", List.of(), 1, 1024);
        check(result.updateCount() == 1 && success.commits == 1 && success.setAutoCommitTrue == 1,
                "successful commit restores autocommit for healthy reuse");
        System.out.println("JDBC_TRANSACTION_NO_IMPLICIT_COMMIT_PASS");
    }
}
