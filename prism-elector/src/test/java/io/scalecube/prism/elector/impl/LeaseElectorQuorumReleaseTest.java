package io.scalecube.prism.elector.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.Member;
import io.scalecube.prism.consensus.Acceptor;
import io.scalecube.prism.consensus.PeerCaller;
import io.scalecube.prism.consensus.QuorumConsensusStore;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Graceful release (resign / demote) over the <b>real distributed quorum store</b>, not the in-memory
 * one. The in-memory store clears a lease on a {@code null} write, which hid a defect: the quorum store
 * has no "delete", so releasing must be expressed as an already-expired lease. This test drives the
 * elector on a real {@link QuorumConsensusStore} so that path is actually exercised.
 */
@DisplayName("Elector over the real quorum store: graceful resign/demote releases the lease")
class LeaseElectorQuorumReleaseTest {

  private static final String G = "gateway";
  private static final List<String> NODES = List.of("n1", "n2", "n3");

  private final AtomicLong clock = new AtomicLong(1000);
  private final Map<String, Acceptor> acceptors = new HashMap<>();
  private final Map<String, Member> members = new HashMap<>();

  @BeforeEach
  void setup() {
    acceptors.clear();
    for (String n : NODES) {
      acceptors.put(n, new Acceptor());
    }
  }

  private LeaseElector elector(String id) {
    members.put(id, new Member(id, null, id + "@addr", "prism"));
    PeerCaller caller = (peer, req) -> Mono.just(acceptors.get(peer).handle(req, clock.get()));
    QuorumConsensusStore store =
        new QuorumConsensusStore(
            id, NODES, acceptors.get(id), caller, clock::get, Duration.ofMillis(50));
    return new LeaseElector(
        members.get(id), store, x -> Optional.ofNullable(members.get(x)),
        Duration.ofMillis(1000), clock::get);
  }

  /**
   * Given a leader elected over a real majority quorum,
   * When it resigns,
   * Then the lease is released on the quorum (no NPE; another node could take over immediately).
   */
  @Test
  void resignReleasesOverTheQuorum() {
    LeaseElector n1 = elector("n1");
    n1.campaign(G).block();
    assertTrue(n1.currentLeader(G).isPresent(), "n1 acquires a majority lease");

    n1.resign(G).block(); // before the fix this threw NPE inside QuorumConsensusStore.compareAndSet

    assertFalse(n1.currentLeader(G).isPresent(), "graceful resign releases the lease on the quorum");
  }

  /**
   * Given a controller that promoted a node over a real quorum,
   * When it demotes that node,
   * Then the lease is released on the quorum (no NPE).
   */
  @Test
  void demoteReleasesOverTheQuorum() {
    LeaseElector n1 = elector("n1");
    assertTrue(n1.promote(G).block(), "n1 promotes into the free group");
    assertTrue(n1.currentLeader(G).isPresent());

    n1.demote(G).block();

    assertFalse(n1.currentLeader(G).isPresent(), "demote releases the lease on the quorum");
  }
}
