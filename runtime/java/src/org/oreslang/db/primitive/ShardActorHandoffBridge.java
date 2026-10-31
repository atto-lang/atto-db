package org.oreslang.db.primitive;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

/**
 * HOST-ONLY WAL-to-shard actor-turn bridge. NOT a guest API, scheduler,
 * authenticated issuer, durable commit, or an externally observable DB ACK.
 *
 * The real backend MUST supply an authenticated generation-fenced RuntimeQueue
 * plus an independent actor-turn authority. The latter must reject calls from
 * disk workers, callback handoff threads and foreign actor turns.
 *
 * Only one WAL append may be pending. Its CompletionStage is never exposed to
 * the guest. Early and late completion both pass through the bounded
 * WalActorContinuationGate and re-enter a real serialized actor turn.
 */
final class ShardActorHandoffBridge {
  private final WalRuntimeGrant.Context context;
  private final WalRuntimeGrant writer;
  private final WalActorContinuationGate handoff;
  private final ShardPublicationGate shard;
  private volatile boolean quarantined;
  private volatile boolean retired;

  private ShardActorHandoffBridge(WalRuntimeGrant.Context ctx,
                                  WalRuntimeGrant grant,
                                  WalActorContinuationGate.RuntimeQueue mailbox,
                                  ShardPublicationGate.RuntimeAuthority actorTurn) {
    context = Objects.requireNonNull(ctx);
    writer = Objects.requireNonNull(grant);
    Objects.requireNonNull(mailbox);
    Objects.requireNonNull(actorTurn);
    handoff = WalActorContinuationGate.fromTrustedRuntime(ctx, mailbox);
    // The mailbox's generation check alone is insufficient: publication
    // additionally requires a real actor turn and a non-poisoned handoff.
    shard = ShardPublicationGate.fromTrustedRuntime(ctx, grant, trusted ->
        !retired && !quarantined && !handoff.isPoisoned() &&
        actorTurn.isCurrent(trusted));
  }

  /** Only a real runtime may call this with its trusted objects. No DATA API. */
  static ShardActorHandoffBridge fromTrustedRuntime(
      WalRuntimeGrant.Context issued,
      WalRuntimeGrant authorizedWriter,
      WalActorContinuationGate.RuntimeQueue authorizedMailbox,
      ShardPublicationGate.RuntimeAuthority actorTurnAuthority) {
    return new ShardActorHandoffBridge(issued, authorizedWriter,
                                       authorizedMailbox, actorTurnAuthority);
  }

  /**
   * Called on the runtime's serialized shard actor turn. This method only
   * stages and dispatches. No blocking get(), wait(), I/O, or guest ACK.
   * The completion stays within the host bridge and can only publish on a
   * later admitted turn. A lost callback is indistinguishable from UNKNOWN.
   */
  void appendOnActorTurn(WalRuntimeGrant.Context caller, byte[] frame) {
    checkUsable();
    CompletionStage<WalRuntimeGrant.SyncedBoundary> receipt =
        shard.stageAndDispatch(caller, frame);
    try {
      handoff.register(caller, receipt, (synced, failure) -> {
        // WalActorContinuationGate ensures this callback is on an authorized
        // runtime actor turn. Any unchecked failure poisons that gate.
        if (failure != null) {
          shard.markUnknownOnTrustedTurn(context);
        } else {
          shard.publishOnTrustedTurn(context, synced);
        }
      });
    } catch (RuntimeException | Error lostDelivery) {
      // appendAndSync may already have dispatched durable bytes. NEVER
      // reset the shard to OPEN or retry an operation just because callback
      // registration failed. A subsequent operation must be denied.
      quarantined = true;
      throw new RejectedExecutionException(
          "WAL receipt registration unavailable; outcome UNKNOWN", lostDelivery);
    }
  }

  Optional<byte[]> readOnActorTurn(WalRuntimeGrant.Context caller, byte[] key) {
    checkUsable();
    return shard.readPublishedOnTrustedTurn(caller, key);
  }

  ShardPublicationGate.Snapshot snapshotOnActorTurn(WalRuntimeGrant.Context caller) {
    checkUsable();
    return shard.snapshotOnTrustedTurn(caller);
  }

  /** A healthy handoff is required for every subsequent read and write. */
  private void checkUsable() {
    if (retired || quarantined || handoff.isPoisoned()) {
      quarantined = true;
      throw new RejectedExecutionException(
          "shard outcome UNKNOWN or retired; reconcile before restart");
    }
  }

  /** Diagnostic only: never proof of durable publication or guest ACK. */
  boolean isQuarantinedForDiagnostics() {
    return quarantined || handoff.isPoisoned() || shard.isQuarantinedForDiagnostics();
  }

  boolean canAcknowledgeGuest() { return false; }

  /**
   * Called from trusted runtime shutdown; safe off the actor carrier. Async
   * WAL revocation dominates late callbacks. Do not close the FD directly:
   * the primitive keeps it until all disk and handoff tasks are quiescent.
   */
  CompletionStage<Void> retireFromTrustedRuntime() {
    retired = true;
    quarantined = true;
    handoff.close();
    return writer.revokeAndCloseAsync();
  }
}
