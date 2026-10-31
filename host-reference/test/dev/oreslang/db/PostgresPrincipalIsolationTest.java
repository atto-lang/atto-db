package dev.oreslang.db;

import java.sql.*;
import java.util.UUID;

/**
 * Disposable PostgreSQL 17 role/RLS boundary test.
 * This tests database roles independently of ActorPgPool and is NOT evidence
 * that the current single-role pool has adopted this authorization design.
 * Run only against a throwaway database with CREATE ROLE privileges.
 */
public final class PostgresPrincipalIsolationTest {
    private PostgresPrincipalIsolationTest() {}

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static long count(Connection connection, String query) throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet rows = s.executeQuery(query)) {
            if (!rows.next()) throw new AssertionError("missing count");
            return rows.getLong(1);
        }
    }
    static void checkTenant(String url, String role, String password,
                            String other, String schema) throws SQLException {
        String table = schema + ".actor_state";
        try (Connection connection = DriverManager.getConnection(url, role, password)) {
            check(count(connection, "SELECT count(*) FROM " + table) == 2,
                  "wrong tenant row count for " + role);
            check(count(connection, "SELECT count(*) FROM " + table +
                  " WHERE tenant_role='" + other + "'") == 0,
                  "cross-tenant read permitted");
            try (Statement s = connection.createStatement()) {
                check(s.executeUpdate("UPDATE " + table +
                      " SET version=version+1 WHERE tenant_role='" + other + "'") == 0,
                      "cross-tenant UPDATE permitted");
                check(s.executeUpdate("UPDATE " + table +
                      " SET version=version+1 WHERE tenant_role='" + role + "' AND actor_id=1") == 1,
                      "own UPDATE was denied");
                // A custom GUC may be set by a caller, but cannot change current_user.
                try (PreparedStatement set = connection.prepareStatement(
                        "SELECT set_config('ores.tenant_id', ?, false)")) {
                    set.setString(1, other);
                    set.execute();
                }
                check(count(connection, "SELECT count(*) FROM " + table) == 2,
                      "custom GUC bypassed database-role RLS");
                try {
                    s.execute("SET ROLE " + other);
                    throw new AssertionError("ungranted cross-tenant SET ROLE succeeded");
                } catch (SQLException expected) {
                    check("42501".equals(expected.getSQLState()),
                          "SET ROLE failed for unexpected reason: " + expected.getSQLState());
                }
                try {
                    s.executeUpdate("UPDATE " + table +
                        " SET tenant_role='" + other + "' WHERE tenant_role='" + role +
                        "' AND actor_id=1");
                    throw new AssertionError("RLS WITH CHECK allowed cross-tenant ownership");
                } catch (SQLException expected) {
                    check("42501".equals(expected.getSQLState()),
                          "RLS ownership mutation failed for unexpected reason: " +
                          expected.getSQLState());
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String url = required("ORES_TEST_PG_URL");
        String adminUser = required("ORES_TEST_PG_ADMIN_USER");
        String adminPassword = required("ORES_TEST_PG_ADMIN_PASSWORD");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        // All SQL identifiers and passwords here are generated from lowercase hex.
        String schema = "ores_principal_" + suffix;
        String roleA = "ores_ta_" + suffix, roleB = "ores_tb_" + suffix;
        String passwordA = "test_a_" + suffix, passwordB = "test_b_" + suffix;
        boolean aCreated = false, bCreated = false, schemaCreated = false;
        try (Connection admin = DriverManager.getConnection(url, adminUser, adminPassword);
             Statement s = admin.createStatement()) {
            try {
                s.execute("CREATE ROLE " + roleA + " LOGIN PASSWORD '" + passwordA + "'");
                aCreated = true;
                s.execute("CREATE ROLE " + roleB + " LOGIN PASSWORD '" + passwordB + "'");
                bCreated = true;
                s.execute("CREATE SCHEMA " + schema);
                schemaCreated = true;
                s.execute("CREATE TABLE " + schema +
                    ".actor_state (tenant_role text NOT NULL, actor_id int NOT NULL," +
                    " version int NOT NULL DEFAULT 0, PRIMARY KEY (tenant_role, actor_id))");
                try (PreparedStatement p = admin.prepareStatement(
                        "INSERT INTO " + schema + ".actor_state(tenant_role,actor_id) VALUES (?,?)")) {
                    for (String role : new String[] { roleA, roleB }) {
                        for (int i = 1; i <= 2; i++) {
                            p.setString(1, role);
                            p.setInt(2, i);
                            p.addBatch();
                        }
                    }
                    p.executeBatch();
                }
                s.execute("ALTER TABLE " + schema + ".actor_state ENABLE ROW LEVEL SECURITY");
                s.execute("ALTER TABLE " + schema + ".actor_state FORCE ROW LEVEL SECURITY");
                s.execute("CREATE POLICY tenant_role_only ON " + schema +
                    ".actor_state FOR ALL TO " + roleA + "," + roleB +
                    " USING (tenant_role=current_user) WITH CHECK (tenant_role=current_user)");
                s.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + roleA + "," + roleB);
                s.execute("GRANT SELECT,UPDATE ON " + schema +
                    ".actor_state TO " + roleA + "," + roleB);
                checkTenant(url, roleA, passwordA, roleB, schema);
                checkTenant(url, roleB, passwordB, roleA, schema);
                System.out.println("PG17_ROLE_PRINCIPAL_RLS_ISOLATION_PASS");
            } finally {
                try {
                    if (schemaCreated) s.execute("DROP SCHEMA " + schema + " CASCADE");
                } finally {
                    try { if (bCreated) s.execute("DROP ROLE " + roleB); }
                    finally { if (aCreated) s.execute("DROP ROLE " + roleA); }
                }
            }
        }
    }
}
