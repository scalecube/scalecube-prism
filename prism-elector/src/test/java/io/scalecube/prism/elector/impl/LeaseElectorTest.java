package io.scalecube.prism.elector.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.Member;
import io.scalecube.prism.consensus.InMemoryConsensusStore;
import io.scalecube.prism.elector.Leadership;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Singleton elector: lease, fencing & failover")
class LeaseElectorTest {

  private final AtomicLong clock = new AtomicLong(1000);
  private final InMemoryConsensusStore store = new InMemoryConsensusStore();
  private final Map<String, Member> members = new HashMap<>();

  private LeaseElector elector(String id) {
    Member m = new Member(id, null, id + "@addr", "prism");
    members.put(id, m);
    return new LeaseElector(
        m, store, x -> Optional.ofNullable(members.get(x)), Duration.ofMillis(1000), clock::get);
  }

  private static List<Leadership> capture(LeaseElector e, String group) {
    List<Leadership> events = new CopyOnWriteArrayList<>();
    e.leadership(group).subscribe(events::add);
    return events;
  }

  /**
   * Given two members campaigning for the same group,
   * When both campaign,
   * Then exactly one becomes Active and the other does not.
   */
  @Test
  void onlyOneBecomesActive() {
    LeaseElector a = elector("A");
    LeaseElector b = elector("B");
    List<Leadership> ea = capture(a, "gw");
    List<Leadership> eb = capture(b, "gw");

    a.campaign("gw").block();
    b.campaign("gw").block();

    assertTrue(ea.stream().anyMatch(Leadership::active), "A should win");
    assertFalse(eb.stream().anyMatch(Leadership::active), "B must not also be active");
    assertEquals("A", a.currentLeader("gw").orElseThrow().id());
  }

  /**
   * Given an Active leader and a waiting standby,
   * When the leader's lease expires (it stopped renewing) and the standby ticks,
   * Then the standby takes over with a strictly higher fencing epoch.
   */
  @Test
  void standbyTakesOverAfterLeaseExpiresWithHigherEpoch() {
    LeaseElector a = elector("A");
    LeaseElector b = elector("B");
    List<Leadership> ea = capture(a, "gw");
    List<Leadership> eb = capture(b, "gw");

    a.campaign("gw").block(); // A active at its fencing ballot
    b.campaign("gw").block(); // B candidate
    assertFalse(eb.stream().anyMatch(Leadership::active));
    long aEpoch = ea.stream().filter(Leadership::active).findFirst().orElseThrow().epoch();

    clock.addAndGet(1001); // A stops renewing; lease expires
    b.tick();

    Leadership active = eb.stream().filter(Leadership::active).findFirst().orElseThrow();
    assertEquals("B", active.member().id());
    assertTrue(active.epoch() > aEpoch, "fencing token must strictly increase on takeover");
  }

  /**
   * Given leadership has moved to the standby,
   * When the old leader next ticks,
   * Then it discovers it lost and emits a revocation (so it stops acting).
   */
  @Test
  void oldLeaderLearnsItLostOnNextTick() {
    LeaseElector a = elector("A");
    LeaseElector b = elector("B");
    List<Leadership> ea = capture(a, "gw");

    a.campaign("gw").block();
    b.campaign("gw").block();
    clock.addAndGet(1001);
    b.tick(); // B takes over
    a.tick(); // A discovers the lease is no longer its

    assertTrue(ea.stream().anyMatch(l -> !l.active()), "A must be revoked");
  }

  /**
   * Given an Active leader,
   * When it resigns gracefully,
   * Then the standby acquires leadership immediately.
   */
  @Test
  void resignReleasesLeadershipToStandby() {
    LeaseElector a = elector("A");
    LeaseElector b = elector("B");
    List<Leadership> eb = capture(b, "gw");

    a.campaign("gw").block();
    b.campaign("gw").block();
    a.resign("gw").block();

    b.tick();

    assertTrue(eb.stream().anyMatch(Leadership::active));
    assertEquals("B", b.currentLeader("gw").orElseThrow().id());
  }
}
