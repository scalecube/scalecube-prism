package io.scalecube.prism.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@DisplayName("Quorum lease: distributed safety (majority + partition)")
class QuorumConsensusStoreTest {

  private static final List<String> NODES = List.of("n1", "n2", "n3");

  private final AtomicLong clock = new AtomicLong(1000);
  private final Map<String, Acceptor> acceptors = new HashMap<>();
  private final Set<String> partitioned = new HashSet<>();
  private final Map<String, QuorumConsensusStore> stores = new HashMap<>();

  @BeforeEach
  void setUp() {
    for (String n : NODES) {
      acceptors.put(n, new Acceptor());
    }
    for (String n : NODES) {
      String self = n;
      PeerCaller caller =
          (peer, req) -> {
            if (isCut(self, peer)) {
              return Mono.error(new RuntimeException("unreachable"));
            }
            return Mono.just(acceptors.get(peer).handle(req, clock.get()));
          };
      stores.put(
          n,
          new QuorumConsensusStore(
              self, NODES, acceptors.get(self), caller, clock::get, Duration.ofMillis(50)));
    }
  }

  private boolean isCut(String a, String b) {
    return partitioned.contains(a) != partitioned.contains(b);
  }

  private LeaseRecord lease(String owner, long epoch) {
    return new LeaseRecord("gw", owner, epoch, clock.get() + 1000);
  }

  /**
   * Given a fully-connected 3-node quorum,
   * When a node proposes a lease,
   * Then it wins with a majority and the value is readable cluster-wide.
   */
  @Test
  void majorityAcquireSucceeds() {
    assertTrue(stores.get("n1").compareAndSet("gw", null, lease("n1", 1)));
    assertEquals("n1", stores.get("n2").get("gw").orElseThrow().owner());
  }

  /**
   * Given two nodes propose the same epoch concurrently,
   * When both attempt to acquire,
   * Then at most one wins (the other is rejected) — never two leaders.
   */
  @Test
  void concurrentSameEpochYieldsAtMostOne() {
    boolean a = stores.get("n1").compareAndSet("gw", null, lease("n1", 1));
    boolean b = stores.get("n2").compareAndSet("gw", null, lease("n2", 1));
    assertTrue(a);
    assertFalse(b, "second same-epoch proposer must not also win");
  }

  /**
   * Given a network partition isolating one node from the other two,
   * When both sides try to acquire,
   * Then the minority cannot (loses availability), the majority can (keeps safety), and after heal
   * the minority still cannot override the valid majority lease.
   */
  @Test
  void minorityPartitionCannotAcquireButMajorityCan() {
    partitioned.add("n1"); // {n1} | {n2, n3}

    assertFalse(stores.get("n1").compareAndSet("gw", null, lease("n1", 1)), "minority must fail");
    assertTrue(stores.get("n2").compareAndSet("gw", null, lease("n2", 1)), "majority must win");

    partitioned.clear();
    assertFalse(stores.get("n1").compareAndSet("gw", null, lease("n1", 1)));
    assertEquals("n2", stores.get("n1").get("gw").orElseThrow().owner());
  }

  /**
   * Given a node that is NOT a member of the quorum (e.g. it lags a reconfiguration or was
   * reconfigured out) acting as a proposer, reaching only a single real member,
   * When it tries to acquire,
   * Then it must FAIL — a non-member is a pure Paxos proposer and must not count its own local
   * acceptor toward the member majority, or it could win with a minority of real members and fork a
   * reconfiguration (a quorum-intersection violation the partitioned real-reconfiguration fuzz
   * surfaced).
   */
  @Test
  void nonMemberProposerCannotWinWithMinorityOfMembers() {
    acceptors.put("n4", new Acceptor()); // n4 is NOT in NODES {n1,n2,n3}
    PeerCaller reachesOnlyN1 =
        (peer, req) ->
            peer.equals("n1")
                ? Mono.just(acceptors.get(peer).handle(req, clock.get()))
                : Mono.error(new RuntimeException("unreachable"));
    QuorumConsensusStore n4 =
        new QuorumConsensusStore(
            "n4", NODES, acceptors.get("n4"), reachesOnlyN1, clock::get, Duration.ofMillis(50));

    // Only one real member (n1) is reachable — a minority of {n1,n2,n3}. If n4 counted its own
    // acceptor it would reach a false majority of 2 and win; it must not.
    assertFalse(
        n4.compareAndSet("gw", null, lease("n4", 1)),
        "a non-member must not win a lease with only a minority of real members");
  }

  /**
   * Given a leader whose lease has expired,
   * When a standby acquires,
   * Then it takes over with a strictly higher epoch (preserving the fencing token).
   */
  @Test
  void standbyTakesOverAfterExpiryWithHigherEpoch() {
    assertTrue(stores.get("n1").compareAndSet("gw", null, lease("n1", 1)));
    clock.addAndGet(1001);
    assertTrue(stores.get("n2").compareAndSet("gw", null, lease("n2", 2)));
    assertEquals("n2", stores.get("n3").get("gw").orElseThrow().owner());
    assertEquals(2, stores.get("n3").get("gw").orElseThrow().epoch());
  }
}
