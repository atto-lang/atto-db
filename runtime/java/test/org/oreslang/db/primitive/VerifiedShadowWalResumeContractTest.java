package org.oreslang.db.primitive;

/** Exact Probe-only source contract: this does NOT test any WAL/actor/guest. */
public final class VerifiedShadowWalResumeContractTest {
  private VerifiedShadowWalResumeContractTest() { }
  private static int checks;
  private static void check(boolean ok, String label) {
    checks++;
    if (!ok) throw new AssertionError(label);
  }
  public static void main(String[] args) {
    if (args.length != 0) throw new IllegalArgumentException("unexpected args");
    byte[] digest = new byte[32];
    var probe = new VerifiedShadowWalResumption.Probe(4, 5, 105, 96, 128, digest);
    check(probe.generation() == 4, "generation");
    check(probe.nextSequence() == 5, "sequence");
    check(probe.operationId() == 105, "operation identity");
    check(probe.sourceBytes() == 96, "source offset");
    check(probe.shadowBytes() == 128, "shadow offset");
    check(probe.shadowSha256().length == 32, "digest size");
    check(!probe.canResumeWrites(), "no writer promotion");
    check(!probe.canAcknowledgeGuest(), "no guest ACK");
    digest[0] = 7;
    check(probe.shadowSha256()[0] == 0, "constructor defensively copied");
    byte[] returned = probe.shadowSha256();
    returned[0] = 9;
    check(probe.shadowSha256()[0] == 0, "getter defensively copied");
    check(returned != probe.shadowSha256(), "getter returns independent array");
    boolean nullDenied = false;
    try { new VerifiedShadowWalResumption.Probe(4, 5, 105, 96, 128, null); }
    catch (NullPointerException expected) { nullDenied = true; }
    check(nullDenied, "null digest denied");
    System.out.println("HOST_SHADOW_WAL_CONTRACT_PASS checks=" + checks +
        " (NO live writer or guest ACK)");
  }
}
