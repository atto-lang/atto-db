package org.oreslang.db.primitive;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

/** Host contract tests only; do not infer Graal guest or crash durability. */
public final class WalRuntimeGrantTest {
  private static int checks;
  private static final WalFilePrimitive.Owner OWNER = new WalFilePrimitive.Owner(7, 11, 5, 3);
  private static void ok(boolean value, String why) {
    checks++;
    if (!value) throw new AssertionError(why);
  }
  private static void reject(Class<? extends Throwable> expected, Runnable call) {
    checks++;
    try { call.run(); }
    catch (Throwable actual) {
      if (expected.isInstance(actual)) return;
      throw new AssertionError("expected " + expected + " got " + actual, actual);
    }
    throw new AssertionError("missing " + expected);
  }
  private static byte[] frame(int sequence, int id) {
    ByteBuffer b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {79,68,66,49,1,1});
    b.putInt(7).putInt(3).putInt(sequence).putInt(id);
    b.putShort((short)1).putShort((short)1).put((byte)107).put((byte)118);
    int a = 1, c = 0;
    for (int i = 0; i < 28; ++i) {
      a = (a + (b.get(i) & 255)) % 65521;
      c = (c + a) % 65521;
    }
    b.putShort((short)a).putShort((short)c);
    return b.array();
  }
  private static FileChannel newWal(Path path) throws IOException {
    return FileChannel.open(path, StandardOpenOption.CREATE_NEW,
        StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
  }
  private static void verifyShape() {
    ok(!Modifier.isPublic(WalRuntimeGrant.class.getModifiers()),
       "host grant is package-private");
    for (var constructor : WalRuntimeGrant.Context.class.getDeclaredConstructors()) {
      ok(Modifier.isPrivate(constructor.getModifiers()), "context has no public constructor");
    }
    for (var constructor : WalRuntimeGrant.SyncedBoundary.class.getDeclaredConstructors()) {
      ok(Modifier.isPrivate(constructor.getModifiers()), "sync boundary has no public constructor");
    }
  }
  private static void verifyGrant(Path dir) throws Exception {
    Path path = dir.resolve("grant.wal");
    var context = WalRuntimeGrant.issueContextFromTrustedRuntime(OWNER);
    var forgedEquivalent = WalRuntimeGrant.issueContextFromTrustedRuntime(OWNER);
    var foreign = WalRuntimeGrant.issueContextFromTrustedRuntime(
        new WalFilePrimitive.Owner(8, 11, 5, 3));
    var stale = WalRuntimeGrant.issueContextFromTrustedRuntime(
        new WalFilePrimitive.Owner(7, 11, 5, 2));
    var grant = WalRuntimeGrant.attachPreopenedFromRuntime(newWal(path), context);
    byte[] bytes = frame(1, 42);
    reject(SecurityException.class, () -> grant.appendAndSync(forgedEquivalent, bytes));
    reject(SecurityException.class, () -> grant.appendAndSync(foreign, bytes));
    reject(SecurityException.class, () -> grant.appendAndSync(stale, bytes));
    ok(Files.size(path) == 0, "failed caller admission leaves file empty");
    var synced = grant.appendAndSync(context, bytes).toCompletableFuture().join();
    ok(synced.boundTo(context) && !synced.boundTo(forgedEquivalent),
       "sync evidence bound to exact runtime context object");
    ok(synced.operationId() == 42 && synced.sequence() == 1,
       "sync evidence bound to WAL sequence and operation");
    ok(synced.endOffset() == bytes.length && synced.frameSha256().length == 32,
       "sync evidence binds file length and digest");
    byte[] badDigest = synced.frameSha256();
    badDigest[0] ^= 1;
    ok(!Arrays.equals(badDigest, synced.frameSha256()), "digest defensively copied");
    ok(Arrays.equals(Files.readAllBytes(path), bytes), "real force followed by byte read");
    byte[] changed = frame(2, 43);
    var later = grant.appendAndSync(context, changed).toCompletableFuture().join();
    ok(later.sequence() == 2, "second ordered sync");
    grant.revokeAndCloseAsync().toCompletableFuture().join();
    reject(RejectedExecutionException.class, () -> grant.appendAndSync(context, frame(3,44)));
    ok(Files.size(path) == bytes.length + changed.length,
       "revocation never truncates synced WAL");
    // A second grant to a nonempty WAL cannot bypass recovered history.
    try (var preopened = FileChannel.open(path, StandardOpenOption.READ,
                                         StandardOpenOption.WRITE)) {
      reject(IllegalStateException.class, () -> {
        try { WalRuntimeGrant.attachPreopenedFromRuntime(preopened, context); }
        catch (IOException e) { throw new AssertionError(e); }
      });
    }
  }
  public static void main(String[] args) throws Exception {
    verifyShape();
    Path dir = Files.createTempDirectory("ores-db-runtime-grant-");
    try { verifyGrant(dir); }
    finally {
      try (var contents = Files.list(dir)) {
        contents.forEach(p -> { try { Files.deleteIfExists(p); }
          catch (IOException e) { throw new RuntimeException(e); } });
      }
      Files.deleteIfExists(dir);
    }
    System.out.println("HOST_WAL_RUNTIME_GRANT_PASS checks=" + checks + " (not Oreslang guest)");
  }
}
