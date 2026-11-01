package org.oreslang.db.primitive;

/** Minimal hosted compilation contract; extended real-FD tests run locally. */
public final class VerifiedShadowWalResumeContractTest {
  private VerifiedShadowWalResumeContractTest() { }
  public static void main(String[] args) {
    byte[] hash = new byte[32];
    var diagnostic = new VerifiedShadowWalResumption.Probe(4,5,105,96,128,hash);
    hash[0] = 7;
    if (diagnostic.generation()!=4 || diagnostic.nextSequence()!=5 ||
        diagnostic.operationId()!=105 || diagnostic.sourceBytes()!=96 ||
        diagnostic.shadowBytes()!=128 || diagnostic.shadowSha256()[0]!=0 ||
        diagnostic.canResumeWrites() || diagnostic.canAcknowledgeGuest())
      throw new AssertionError("shadow-only probe contract broken");
    byte[] copy = diagnostic.shadowSha256();
    copy[0] = 9;
    if (diagnostic.shadowSha256()[0]!=0)
      throw new AssertionError("probe digest getter leaked mutable storage");
    // Constructor rejects a null digest: a diagnostic without a stable hash
    // cannot be trusted for even source-only comparisons.
    boolean refused=false;
    try { new VerifiedShadowWalResumption.Probe(4,5,105,96,128,null); }
    catch(NullPointerException expected) { refused=true; }
    if(!refused)throw new AssertionError("null digest accepted");
    if(diagnostic.canResumeWrites()||diagnostic.canAcknowledgeGuest())
      throw new AssertionError("probe exposed writer or guest authority");
    if(diagnostic.shadowSha256().length!=32 ||
       diagnostic.shadowSha256()[0]!=0)
      throw new AssertionError("defensive SHA256 copy or size broken");
    System.out.println("HOST_SHADOW_WAL_CONTRACT_PASS checks=12 (NO live writer or guest ACK)");
  }
}
