package io.scalecube.prism.elector.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.Member;
import io.scalecube.prism.consensus.ConsensusStore;
import io.scalecube.prism.consensus.LeaseRecord;
import io.scalecube.prism.consensus.PrepareResult;
import io.scalecube.prism.elector.Leadership;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the ADR/review item O1 property: the elector's internal lock is <b>never held while a
 * blocking consensus-store round runs</b>, and a {@code resign} that races an in-flight acquire does
 * not leak leadership. Uses a hand-controlled {@link ConsensusStore} that parks on a latch inside a
 * chosen operation, so the test can observe the elector while a round is mid-flight.
 */
@DisplayName("Elector: consensus I/O runs off the lock")
class LeaseElectorConcurrencyTest {

  private static final String GROUP = "gw";
  private static final Duration TTL = Duration.ofSeconds(30);

  /**
   * Given a campaign whose store round is parked deep inside {@code store.get} on a worker thread,
   * When the main thread calls a lock-only method ({@code leadership}),
   * Then it returns promptly — proving the lock is not held across the blocking store I/O. Against
   * the old "everything under the lock" elector this call would block until the round completes.
   */
  @Test
  void lockIsFreeWhileAStoreRoundIsInFlight() throws Exception {
    GatedStore store = new GatedStore();
    store.blockOnGet = true;
    LeaseElector elector = elector("n1", store);

    Thread worker = new Thread(() -> elector.campaign(GROUP).block(), "campaign");
    worker.start();
    assertTrue(store.entered.await(5, TimeUnit.SECONDS), "round never entered store I/O");

    // The worker is parked inside store.get() holding NO lock; this must not block.
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          elector.leadership(GROUP);
          elector.affinity(GROUP, () -> io.scalecube.prism.elector.Preference.STANDBY,
              Duration.ZERO, false);
        },
        "a lock-only call blocked while a store round was in flight — lock held across I/O");

    store.gate.countDown(); // let the round finish
    worker.join(TimeUnit.SECONDS.toMillis(5));
  }

  /**
   * Given an acquire parked at the ACCEPT (compareAndSet) on a worker thread,
   * When the main thread {@code resign}s the group before the ACCEPT returns,
   * Then on commit the elector detects it no longer wants the lease, releases it, and emits no
   * {@code active} leadership — no leaked believer (exercises the contend() undo path).
   */
  @Test
  void resignDuringInFlightAcquireDoesNotLeakLeadership() throws Exception {
    GatedStore store = new GatedStore();
    store.blockOnCas = true;
    LeaseElector elector = elector("n1", store);

    List<Leadership> events = new CopyOnWriteArrayList<>();
    elector.leadership(GROUP).subscribe(events::add);

    Thread worker = new Thread(() -> elector.campaign(GROUP).block(), "campaign");
    worker.start();
    assertTrue(store.entered.await(5, TimeUnit.SECONDS), "acquire never reached ACCEPT");

    elector.resign(GROUP).block(); // resign while the ACCEPT is parked
    store.gate.countDown(); // ACCEPT now returns "won"
    worker.join(TimeUnit.SECONDS.toMillis(5));

    assertFalse(
        events.stream().anyMatch(Leadership::active),
        "won-then-resigned acquire leaked an active leadership event");
    assertTrue(
        store.current == null || store.current.isExpired(Long.MAX_VALUE),
        "the won-then-resigned lease was not released");
  }

  private static LeaseElector elector(String id, ConsensusStore store) {
    Member m = new Member(id, null, id + "@local", "prism");
    return new LeaseElector(
        m, store, x -> Optional.of(m), TTL, () -> 0L); // fixed clock; nothing expires mid-test
  }

  /** A {@link ConsensusStore} that parks on a latch inside one chosen operation. */
  private static final class GatedStore implements ConsensusStore {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch gate = new CountDownLatch(1);
    volatile boolean blockOnGet;
    volatile boolean blockOnCas;
    volatile LeaseRecord current;

    @Override
    public Optional<LeaseRecord> get(String group) {
      if (blockOnGet) {
        park();
      }
      return Optional.ofNullable(current);
    }

    @Override
    public boolean compareAndSet(String group, LeaseRecord expected, LeaseRecord next) {
      if (blockOnCas) {
        park();
      }
      current = next;
      return true;
    }

    @Override
    public PrepareResult prepare(String group, long ballot) {
      return PrepareResult.of(true, current, 0L);
    }

    private void park() {
      entered.countDown();
      try {
        gate.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
