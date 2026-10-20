package dev.oreslang.db;
import java.io.PrintWriter;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Run only against an isolated disposable PostgreSQL 17 database. */
public final class PostgresIntegrationTest {
    static String env(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static class Source implements DataSource {
        final String url,user,password;
        final AtomicInteger opens=new AtomicInteger();
        Source(String url,String user,String password){this.url=url;this.user=user;this.password=password;}
        @Override public Connection getConnection() throws SQLException {
            var connection=DriverManager.getConnection(url,user,password);
            opens.incrementAndGet();
            return connection;
        }
        @Override public Connection getConnection(String user,String password)throws SQLException {
            return DriverManager.getConnection(url,user,password);
        }
        @Override public PrintWriter getLogWriter(){return null;}
        @Override public void setLogWriter(PrintWriter writer){}
        @Override public void setLoginTimeout(int timeout)throws SQLException{DriverManager.setLoginTimeout(timeout);}
        @Override public int getLoginTimeout(){return DriverManager.getLoginTimeout();}
        @Override public Logger getParentLogger(){return Logger.getAnonymousLogger();}
        @Override public <T>T unwrap(Class<T> t)throws SQLException{throw new SQLException("not supported");}
        @Override public boolean isWrapperFor(Class<?> t){return false;}
    }
    public static void main(String[] args)throws Exception{
        String url=env("ORES_TEST_PG_URL");
        String adminUser=env("ORES_TEST_PG_ADMIN_USER");
        String adminPassword=env("ORES_TEST_PG_ADMIN_PASSWORD");
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,14);
        String schema="ores_pool_"+suffix;
        String role="ores_actor_"+suffix;
        String password="disposable_test_"+suffix;
        try(Connection admin=DriverManager.getConnection(url,adminUser,adminPassword);
            Statement sql=admin.createStatement()){
            // Identifiers are internally generated lowercase hex, not user-controlled.
            sql.execute("CREATE ROLE "+role+" LOGIN PASSWORD '"+password+"'");
            try{
                sql.execute("CREATE SCHEMA "+schema);
                sql.execute("CREATE TABLE "+schema+".actor_state (tenant_id text NOT NULL,actor_id int NOT NULL,version int NOT NULL DEFAULT 0, PRIMARY KEY(tenant_id,actor_id))");
                sql.execute("ALTER TABLE "+schema+".actor_state ENABLE ROW LEVEL SECURITY");
                sql.execute("ALTER TABLE "+schema+".actor_state FORCE ROW LEVEL SECURITY");
                sql.execute("CREATE POLICY tenant_only ON "+schema+".actor_state FOR ALL TO "+role+" USING (tenant_id=current_setting('ores.tenant_id',true)) WITH CHECK (tenant_id=current_setting('ores.tenant_id',true))");
                sql.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);
                sql.execute("GRANT SELECT,UPDATE ON "+schema+".actor_state TO "+role);
                try(PreparedStatement insert=admin.prepareStatement("INSERT INTO "+schema+".actor_state(tenant_id,actor_id) VALUES (?,?)")){
                    for(int i=0;i<1000;i++) for(String t:List.of("tenant-a","tenant-b")){
                        insert.setString(1,t);insert.setInt(2,i);insert.addBatch();
                    }
                    insert.executeBatch();
                }
                var provider=new Source(url,role,password);
                String read="SELECT tenant_id,actor_id,pg_backend_pid() FROM "+schema+".actor_state WHERE actor_id=?";
                String write="UPDATE "+schema+".actor_state SET version=version+1 WHERE actor_id=?";
                var templates=List.of(new ActorPgPool.Template("read",read,1,true),
                                      new ActorPgPool.Template("write",write,1,false));
                try(ActorPgPool pool=new ActorPgPool(provider,templates,8,1500,1000,3,65536)){
                    var a=pool.grantTenant("tenant-a",Set.of("read","write"));
                    var b=pool.grantTenant("tenant-b",Set.of("read"));
                    List<CompletableFuture<ActorPgPool.Outcome>> all=new ArrayList<>();
                    for(int i=0;i<1000;i++) all.add(pool.submit(i%2==0?a:b,"read",List.of(i)));
                    CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).get(120,TimeUnit.SECONDS);
                    Set<Integer> backendPids=new HashSet<>();
                    for(int i=0;i<1000;i++){
                        var rows=all.get(i).join().rows();
                        check(rows.size()==1,"PostgreSQL RLS leaked cross-tenant rows: "+i);
                        check(rows.get(0).get(0).equals(i%2==0?"tenant-a":"tenant-b"),"wrong RLS tenant");
                        check(((Number)rows.get(0).get(1)).intValue()==i,"wrong actor id");
                        backendPids.add(((Number)rows.get(0).get(2)).intValue());
                    }
                    check(backendPids.size()<=8 && provider.opens.get()<=8,"too many database connections");
                    try{pool.submit(b,"write",List.of(0));throw new AssertionError("unauthorized write accepted");}
                    catch(SecurityException expected){}
                    check(pool.submit(a,"write",List.of(0)).get(15,TimeUnit.SECONDS).updateCount()==1,"cross-tenant UPDATE or bad write");
                    pool.revoke(a);
                    try{pool.submit(a,"read",List.of(0));throw new AssertionError("revoked actor permitted");}
                    catch(SecurityException expected){}
                    System.out.println("ACTOR_PG_REAL_1000_CLIENT_8_CONNECTION_RLS_PASS physical_backends="+backendPids.size());
                    pool.close();
                    check(pool.awaitTermination(15,TimeUnit.SECONDS),"pool connections not released");
                }
            } finally {
                try{sql.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
                finally{sql.execute("DROP ROLE IF EXISTS "+role);}
            }
        }
    }
}
