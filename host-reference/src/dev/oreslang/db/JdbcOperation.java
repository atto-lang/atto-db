package dev.oreslang.db;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
final class JdbcOperation {
    static ActorPgPool.Outcome execute(Connection connection, ActorPgPool.Template template, String tenant, List<Object> arguments, int maxRows, int maxResultBytes) throws SQLException {
        connection.setReadOnly(template.readOnly());
        connection.setAutoCommit(false);
        boolean committed = false;
        try {
            try (PreparedStatement context = connection.prepareStatement("SELECT set_config('ores.tenant_id', ?, true)")) {
                context.setString(1, tenant);
                context.execute();
            }
            ActorPgPool.Outcome result;
            try (PreparedStatement p = connection.prepareStatement(template.sql())) {
                p.setQueryTimeout(30);
                for (int i = 0; i < arguments.size(); i++) {
                    Object v = arguments.get(i);
                    if (v instanceof ActorPgPool.Bytes b) p.setBytes(i + 1, b.value());
                    else p.setObject(i + 1, v);
                }
                boolean rowResult = p.execute();
                if (rowResult) {
                    List<List<Object>> rows = new ArrayList<>();
                    int size = 0;
                    try (ResultSet r = p.getResultSet()) {
                        int columns = r.getMetaData().getColumnCount();
                        if (columns < 0 || columns > 256) throw new SQLException("invalid column count");
                        while (r.next()) {
                            if (rows.size() >= maxRows) throw new SQLException("result row budget exceeded");
                            List<Object> row = new ArrayList<>();
                            for (int i = 1; i <= columns; i++) {
                                Object raw = r.getObject(i);
                                Object v = ActorPgValues.copyValue(raw);
                                size = Math.addExact(size, valueSize(v));
                                if (size > maxResultBytes) throw new SQLException("result byte budget exceeded");
                                row.add(v);
                            }
                            rows.add(Collections.unmodifiableList(row));
                        }
                    }
                    result = new ActorPgPool.Outcome(-1, List.copyOf(rows));
                } else result = new ActorPgPool.Outcome(p.getUpdateCount(), List.of());
            }
            connection.commit();
            committed = true;
            return result;
        } catch (Exception error) {
            try { connection.rollback(); } catch (SQLException rollbackError) { error.addSuppressed(rollbackError); }
            if (error instanceof SQLException sql) throw sql;
            throw new SQLException("database result rejected", error);
        } finally {
            // JDBC setAutoCommit(true) can commit a pending transaction.
            // Failed execution, COMMIT, or ROLLBACK means the owner must discard
            // this connection, not silently commit uncertain changes.
            if (committed) connection.setAutoCommit(true);
        }
    }
    private static int valueSize(Object v) {
        if (v == null) return 1;
        if (v instanceof ActorPgPool.Bytes b) return b.value().length;
        if (v instanceof String s) return s.getBytes(StandardCharsets.UTF_8).length;
        return 64;
    }
}
