package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Test-only host interop driver. This is NOT an Oreslang Java guest compiler. */
public final class Odb1JavaWireInterop {
  private Odb1JavaWireInterop() { }

  private static byte[] frame(int tenant, int generation, int sequence, int operation,
                              int kind, byte[] key, byte[] value) {
    if (tenant <= 0 || generation <= 0 || sequence <= 0 || operation <= 0 ||
        (kind != 1 && kind != 2) || key.length < 1 || key.length > 64 ||
        value.length > 1024 || (kind == 2 && value.length != 0))
      throw new IllegalArgumentException("invalid test frame");
    ByteBuffer buf=ByteBuffer.allocate(30+key.length+value.length).order(ByteOrder.LITTLE_ENDIAN);
    buf.put(new byte[]{79,68,66,49,1,(byte)kind});
    buf.putInt(tenant).putInt(generation).putInt(sequence).putInt(operation);
    buf.putShort((short)key.length).putShort((short)value.length).put(key).put(value);
    int a=1, b=0;
    for (int i=0;i<buf.position();i++) {
      a=(a+Byte.toUnsignedInt(buf.get(i)))%65521;
      b=(b+a)%65521;
    }
    buf.putShort((short)a).putShort((short)b);
    return buf.array();
  }
  private static void write(Path dir,String filename,byte[] bytes) throws IOException {
    Files.write(dir.resolve(filename),bytes);
  }
  private static void emit(Path dir) throws IOException {
    Files.createDirectories(dir);
    write(dir,"empty.wal",new byte[0]);
    byte[] key={107}, value={118};
    byte[] put=frame(7,3,1,42,1,key,value);
    write(dir,"single.wal",put);
    byte[] second=frame(7,4,2,43,2,key,new byte[0]);
    byte[] joined=Arrays.copyOf(put,put.length+second.length);
    System.arraycopy(second,0,joined,put.length,second.length);
    write(dir,"two.wal",joined);
    write(dir,"binary.wal",frame(7,Integer.MAX_VALUE,1,Integer.MAX_VALUE,1,
        new byte[]{0,(byte)255,(byte)128},new byte[]{0,1,(byte)255,(byte)128}));
    byte[] bigKey=new byte[64],bigValue=new byte[1024];
    for(int i=0;i<bigKey.length;i++)bigKey[i]=(byte)i;
    for(int i=0;i<bigValue.length;i++)bigValue[i]=(byte)i;
    write(dir,"maximum.wal",frame(7,3,1,42,1,bigKey,bigValue));
  }
  private static int inspect(Path source,int tenant) throws IOException {
    try(FileChannel ch=FileChannel.open(source,StandardOpenOption.READ)) {
      try {
        var r=ReadOnlyWalReplay.inspectPreopened(ch,tenant);
        System.out.printf("OK records=%d bytes=%d last_sequence=%d last_generation=%d%n",
            r.records().size(),r.bytesChecked(),r.lastSequence(),r.lastGeneration());
        return 0;
      } catch(ReadOnlyWalReplay.InvalidWal e) {
        System.out.printf("INVALID reason=%s at=%d%n",e.reason(),e.offset());
        return 1;
      }
    }
  }
  public static void main(String[]args) throws Exception {
    if(args.length==2 && args[0].equals("emit")) { emit(Path.of(args[1])); return; }
    if(args.length==3 && args[0].equals("inspect")) {
      int status=inspect(Path.of(args[1]),Integer.parseInt(args[2]));
      if(status!=0)System.exit(status);
      return;
    }
    System.err.println("Usage: emit DIR | inspect FILE TENANT");
    System.exit(64);
  }
}
