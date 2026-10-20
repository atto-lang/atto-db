package dev.oreslang.db;

import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Independent host-provider test; this is not a real PostgreSQL execution test. */
public final class ActorPgPoolTest {
    static class Fake implements DataSource {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch unblock = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        static <T> T proxy(Class<T> api, InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, handler);
        }
        @Override public Connection getConnection() {
            opened.incrementAndGet();
            String[] tenant = {""};
            return proxy(Connection.class, (conn, call, args) -> {
                switch(call.getName()) {
                    case "prepareStatement" -> {
                        String sql = (String) args[0];
                        Map<Integer,Object> bound = new HashMap<>();
                        return proxy(PreparedStatement.class, (stmt,m,a) -> {
                            switch(m.getName()) {
                                case "setString","setObject","setBytes" -> {bound.put((Integer)a[0],a[1]);return null;}
                                case "execute" -> {
                                    if (sql.startsWith("SELECT set_config")) {
                                        tenant[0] = (String) bound.get(1);
                                        return true;
                                    }
                                    int active = inFlight.incrementAndGet();
                                    peak.accumulateAndGet(active, Math::max);
                                    try {
                                        if (sql.startsWith("UPDATE blocking")) {
                                            entered.countDown();
                                            if (!unblock.await(10,TimeUnit.SECONDS)) throw new SQLException("timeout");
                                        }
                                        if (sql.startsWith("UPDATE fail")) throw new SQLException("simulated failure");
                                    } finally {inFlight.decrementAndGet();}
                                    return sql.startsWith("SELECT");
                                }
                                case "getResultSet" -> {
                                    int[] row={0};
                                    return proxy(ResultSet.class, (result,rs,ra) -> {
                                        if (rs.getName().equals("next")) return row[0]++ == 0;
                                        if (rs.getName().equals("getObject")) return ((Integer)ra[0]) == 1 ? tenant[0] : new byte[]{1,2,3};
                                        if (rs.getName().equals("getMetaData"))
                                            return proxy(ResultSetMetaData.class,(meta,mm,ma) -> mm.getName().equals("getColumnCount")?2:defaultValue(mm.getReturnType()));
                                        return defaultValue(rs.getReturnType());
                                    });
                                }
                                case "getUpdateCount" -> {return 1;}
                                case "setQueryTimeout", "close" -> {return null;}
                            }
                            return defaultValue(m.getReturnType());
                        });
                    }
                    case "setReadOnly","setAutoCommit","commit","rollback","close" -> {return null;}
                }
                return defaultValue(call.getReturnType());
            });
        }
        static Object defaultValue(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        }
        @Override public Connection getConnection(String a,String b){return getConnection();}
        @Override public PrintWriter getLogWriter(){return null;}
        @Override public void setLogWriter(PrintWriter p){}
        @Override public void setLoginTimeout(int t){}
        @Override public int getLoginTimeout(){return 0;}
        @Override public Logger getParentLogger(){return Logger.getAnonymousLogger();}
        @Override public <T>T unwrap(Class<T> t) throws SQLException {throw new SQLException();}
        @Override public boolean isWrapperFor(Class<?> t){return false;}
    }
    static void check(boolean good,String what){if(!good) throw new AssertionError(what);}
    static ActorPgPool pool(Fake ds,int connections,int global,int perTenant){
        var templates=List.of(
            new ActorPgPool.Template("read","SELECT mock",0,true),
            new ActorPgPool.Template("block","UPDATE blocking SET x=?",1,false),
            new ActorPgPool.Template("fail","UPDATE fail SET x=?",1,false));
        return new ActorPgPool(ds,templates,connections,global,perTenant,10,65536);
    }
    public static void main(String[] args)throws Exception{
        Fake ds=new Fake();
        try (ActorPgPool p=pool(ds,1,2,2)){
            var a=p.grantTenant("tenant-a",Set.of("read","block","fail"));
            var b=p.grantTenant("tenant-b",Set.of("read"));
            check("tenant-a".equals(p.submit(a,"read",List.of()).get(5,TimeUnit.SECONDS).rows().get(0).get(0)),"tenant A");
            check("tenant-b".equals(p.submit(b,"read",List.of()).get(5,TimeUnit.SECONDS).rows().get(0).get(0)),"tenant B");
            try{p.submit(b,"block",List.of(5));throw new AssertionError("grant bypass");}catch(SecurityException ok){}
            var blocked=p.submit(a,"block",List.of(5));
            check(ds.entered.await(5,TimeUnit.SECONDS),"block started");
            var cancelled=p.submit(b,"read",List.of());
            check(cancelled.cancel(true),"cancel queued");
            var queued=p.submit(b,"read",List.of());
            try{p.submit(a,"read",List.of());throw new AssertionError("admission bypass");}catch(IllegalStateException ok){}
            ds.unblock.countDown();
            blocked.get(5,TimeUnit.SECONDS); queued.get(5,TimeUnit.SECONDS);
            var blob=(ActorPgPool.Bytes)p.submit(a,"read",List.of()).get(5,TimeUnit.SECONDS).rows().get(0).get(1);
            blob.value()[0]=99;
            check(blob.value()[0]==1,"defensive bytes");
            try{p.submit(a,"fail",List.of(2)).get(5,TimeUnit.SECONDS);throw new AssertionError("failure accepted");}catch(ExecutionException ok){}
            p.submit(a,"read",List.of()).get(5,TimeUnit.SECONDS);
            check(ds.opened.get()==2,"connection replacement after failure");
            var nested=p.submit(a,"read",List.of()).thenApply(result -> p.submit(b,"read",List.of()).join());
            check(nested.get(5,TimeUnit.SECONDS).rows().size()==1,"no owner callback deadlock");
            p.revoke(a);
            try{p.submit(a,"read",List.of());throw new AssertionError("revoke bypass");}catch(SecurityException ok){}
            p.submit(b,"read",List.of()).get(5,TimeUnit.SECONDS);
        }
        Fake load=new Fake();
        try (ActorPgPool p=pool(load,8,1500,1000)){
            var a=p.grantTenant("tenant-a",Set.of("read"));
            var b=p.grantTenant("tenant-b",Set.of("read"));
            List<CompletableFuture<ActorPgPool.Outcome>> futures=new ArrayList<>();
            for(int i=0;i<1000;i++) futures.add(p.submit(i%2==0?a:b,"read",List.of()));
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(15,TimeUnit.SECONDS);
            for(int i=0;i<1000;i++)
                check(futures.get(i).join().rows().get(0).get(0).equals(i%2==0?"tenant-a":"tenant-b"),"1000-client tenant isolation");
            check(load.opened.get()<=8 && load.peak.get()<=8,"8 physical connections");
        }
        System.out.println("ACTOR_PG_POOL_1000_CLIENT_8_CONNECTION_FAKE_PASS");
        System.out.println("ACTOR_PG_POOL_HOST_CONTRACT_PASS");
    }
}
