package io.scalecube.prism.registry.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.ClusterMessageHandler;
import io.scalecube.cluster.membership.MembershipEvent;
import io.scalecube.cluster.transport.api.Message;
import io.scalecube.prism.registry.ConsistencyTier;
import io.scalecube.prism.registry.RegistryEvent;
import io.scalecube.prism.registry.ServiceEntry;
import io.scalecube.prism.versioning.HybridTimestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Service registry: dissemination & lifecycle over gossip")
class GossipServiceRegistryTest {

  /**
   * Given a provider registers a service and updates and then deregisters it,
   * When each change is gossiped,
   * Then a consumer node converges to the same view at every step.
   */
  @Test
  void propagatesRegisterUpdateDeregister() {
    Wiring w = new Wiring();

    w.a.register("orders", Map.of("weight", "100"), ConsistencyTier.CAUSAL).block();
    assertEquals("100", weight(w.b.lookup("orders")));

    w.a.update("orders", "weight", "0").block();
    assertEquals("0", weight(w.b.lookup("orders")));

    w.a.deregister("orders").block();
    assertTrue(w.b.lookup("orders").isEmpty());
  }

  /**
   * Given a service already known to a node,
   * When a new subscriber starts watching,
   * Then it first receives the current catalog as a snapshot, then live changes.
   */
  @Test
  void watchEmitsSnapshotThenLiveChanges() {
    Wiring w = new Wiring();
    w.a.register("orders", Map.of(), ConsistencyTier.CAUSAL).block(); // already known to b

    List<RegistryEvent> seen = new CopyOnWriteArrayList<>();
    w.b.watch().subscribe(seen::add);

    assertEquals(1, seen.size());
    assertEquals(RegistryEvent.Type.REGISTERED, seen.get(0).type());
    assertEquals("orders", seen.get(0).entry().service());

    w.a.register("billing", Map.of(), ConsistencyTier.CAUSAL).block();
    assertTrue(
        seen.stream()
            .anyMatch(e -> e.type() == RegistryEvent.Type.REGISTERED
                && e.entry().service().equals("billing")));
  }

  /**
   * Given a node holds services owned by a peer,
   * When that peer is removed from the membership (it died),
   * Then the peer's services are purged and reported as EXPIRED (membership is the tombstone).
   */
  @Test
  void memberRemovalPurgesOwnersEntries() {
    Wiring w = new Wiring();
    w.a.register("orders", Map.of(), ConsistencyTier.CAUSAL).block();
    assertEquals(1, w.b.lookup("orders").size());

    List<RegistryEvent> seen = new CopyOnWriteArrayList<>();
    w.b.watch().subscribe(seen::add);

    w.hb.onMembershipEvent(MembershipEvent.createRemoved(w.ca.member(), null, 0L));

    assertTrue(w.b.lookup("orders").isEmpty());
    assertTrue(seen.stream().anyMatch(e -> e.type() == RegistryEvent.Type.EXPIRED));
  }

  /**
   * Given a node has converged to a newer version of an entry,
   * When an out-of-order, older-version delta for the same key arrives,
   * Then it is rejected by last-writer-wins and the newer value is retained.
   */
  @Test
  void staleRemoteUpdateIsRejected() {
    Wiring w = new Wiring();
    w.a.register("orders", Map.of("weight", "100"), ConsistencyTier.CAUSAL).block();
    w.a.update("orders", "weight", "50").block();

    ServiceEntryImpl stale =
        new ServiceEntryImpl(
            "orders", "A", "A@addr", Map.of("weight", "999"),
            new HybridTimestamp(0, 0), ConsistencyTier.CAUSAL, true);
    w.hb.onGossip(
        Message.withData(RegistryGossipCodec.encode(RegistryGossip.of(stale, false)))
            .qualifier(GossipServiceRegistry.QUALIFIER)
            .build());

    assertEquals("50", weight(w.b.lookup("orders")));
  }

  private static String weight(Collection<ServiceEntry> entries) {
    return new ArrayList<>(entries).get(0).properties().get("weight");
  }

  /** Two registries wired to in-memory clusters that deliver gossip straight to each other. */
  private static final class Wiring {
    final GossipServiceRegistry a = new GossipServiceRegistry();
    final GossipServiceRegistry b = new GossipServiceRegistry();
    final FakeCluster ca = new FakeCluster("A", "A@addr");
    final FakeCluster cb = new FakeCluster("B", "B@addr");
    final ClusterMessageHandler ha;
    final ClusterMessageHandler hb;

    Wiring() {
      ha = a.bind(ca);
      hb = b.bind(cb);
      ca.peer = hb; // a's gossip is delivered to b
      cb.peer = ha; // b's gossip is delivered to a
    }
  }
}
