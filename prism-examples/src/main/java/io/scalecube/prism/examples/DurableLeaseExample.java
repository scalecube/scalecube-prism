package io.scalecube.prism.examples;

import io.scalecube.prism.consensus.Acceptor;
import io.scalecube.prism.consensus.LeaseRecord;
import io.scalecube.prism.consensus.LeaseRequest;
import io.scalecube.prism.persistence.FileLeaseJournal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Durability of the consensus safety kernel. An {@link Acceptor} backed by a
 * {@link FileLeaseJournal} persists every acceptance write-ahead, so a crash never forgets the
 * fencing high-water: a recovered acceptor still rejects any epoch ≤ what it accepted, even though
 * the lease itself has expired.
 *
 * <p>This is what makes leadership safe across restarts — without it, a restarted node could hand
 * out a stale (lower) fencing epoch. Shown at the consensus layer so it is self-contained.
 */
public final class DurableLeaseExample {

  /**
   * Runs the example.
   *
   * @param args ignored
   * @throws IOException if the temp journal cannot be created
   */
  public static void main(String[] args) throws IOException {
    Path dir = Files.createTempDirectory("prism-durable");
    Path journal = dir.resolve("lease.journal");
    long now = System.currentTimeMillis();

    // A leader's acceptor durably accepts a lease at epoch 5.
    Acceptor before = new Acceptor(new FileLeaseJournal(journal));
    before.handle(LeaseRequest.accept(new LeaseRecord("gw", "node-1", 5, now + 10_000)), now);
    System.out.println("accepted: node-1 @ epoch 5 (durably journaled)");

    // -- crash & restart: a brand-new acceptor recovers from the same journal --
    Acceptor after = new Acceptor(new FileLeaseJournal(journal));
    LeaseRecord recovered = after.handle(LeaseRequest.get("gw"), now).currentLease().orElse(null);
    System.out.println("recovered after restart: " + recovered);

    // Some time later the lease has expired; a new owner tries to take over.
    long later = now + 20_000;
    LeaseRecord stale = new LeaseRecord("gw", "node-2", 4, later + 10_000);
    LeaseRecord higher = new LeaseRecord("gw", "node-2", 6, later + 10_000);
    boolean staleRejected = !after.handle(LeaseRequest.accept(stale), later).ok();
    boolean higherAccepted = after.handle(LeaseRequest.accept(higher), later).ok();

    System.out.println("epoch 4 (<= durable floor) rejected? " + staleRejected);
    System.out.println("epoch 6 (>  durable floor) accepted? " + higherAccepted);
    System.out.println(
        "=> the fencing epoch never regresses across a crash (monotone, durable).");
  }
}
