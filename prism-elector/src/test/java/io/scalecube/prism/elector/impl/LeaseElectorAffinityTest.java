package io.scalecube.prism.elector.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.Member;
import io.scalecube.prism.consensus.InMemoryConsensusStore;
import io.scalecube.prism.elector.Preference;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Leader affinity (ADR-0016): preference-biased, sticky, no-failback; promote/demote")
class LeaseElectorAffinityTest {

  private static final String G = "gateway";
  private static final Duration TTL = Duration.ofMillis(1000);

  private final AtomicLong clock = new AtomicLong(1000);
  private final InMemoryConsensusStore store = new InMemoryConsensusStore();
  private final Map<String, Member> members = new HashMap<>();

  private LeaseElector elector(String id) {
    Member m = new Member(id, null, id + "@addr", "prism");
    members.put(id, m);
    return new LeaseElector(m, store, x -> Optional.ofNullable(members.get(x)), TTL, clock::get);
  }

  private String leader(LeaseElector e) {
    return e.currentLeader(G).map(Member::id).orElse("none");
  }

  // ---- Mode A: preference-biased autonomous election ----

  /**
   * Given a preferred and a standby candidate with a large yield window, campaigning in any order,
   * When they campaign,
   * Then the preferred candidate wins (the standby yields) — leadership is not decided by call order.
   */
  @Test
  void preferredWinsOverStandbyWithinYieldWindow() {
    LeaseElector a = elector("a");
    LeaseElector b = elector("b");
    a.affinity(G, () -> Preference.PREFERRED, Duration.ofMillis(5000), false);
    b.affinity(G, () -> Preference.STANDBY, Duration.ofMillis(5000), false);

    b.campaign(G).block(); // standby campaigns FIRST — but must yield
    a.campaign(G).block();

    assertEquals("a", leader(a), "the preferred candidate wins regardless of campaign order");
  }

  /**
   * Given a lone standby with a yield window and no preferred candidate,
   * When the yield window elapses,
   * Then it takes leadership (the availability escape hatch).
   */
  @Test
  void standbyAcquiresAfterYieldWindowWhenNoPreferred() {
    LeaseElector b = elector("b");
    b.affinity(G, () -> Preference.STANDBY, Duration.ofMillis(1000), false);

    b.campaign(G).block();
    assertEquals("none", leader(b), "standby holds back during the yield window");

    clock.addAndGet(1500); // past the yield window
    b.tick();
    assertEquals("b", leader(b), "standby takes over once no preferred candidate appeared");
  }

  /**
   * Given an ineligible candidate,
   * When it campaigns and ticks,
   * Then it never becomes leader.
   */
  @Test
  void ineligibleNeverLeads() {
    LeaseElector b = elector("b");
    b.affinity(G, () -> Preference.INELIGIBLE, Duration.ZERO, false);

    b.campaign(G).block();
    b.tick();
    clock.addAndGet(5000);
    b.tick();
    assertEquals("none", leader(b), "an ineligible node must never lead");
  }

  /**
   * Given a standby that already leads and a preferred node that returns later,
   * When the preferred node campaigns,
   * Then it does NOT preempt the healthy leader (no automatic failback).
   */
  @Test
  void preferredDoesNotPreemptHealthyLeader() {
    LeaseElector b = elector("b");
    LeaseElector a = elector("a");
    b.affinity(G, () -> Preference.STANDBY, Duration.ZERO, false);
    a.affinity(G, () -> Preference.PREFERRED, Duration.ZERO, false);

    b.campaign(G).block(); // b leads (yield 0)
    assertEquals("b", leader(b));

    a.campaign(G).block(); // a is preferred but must not steal a valid lease
    a.tick();
    assertEquals("b", leader(b), "a returning preferred node never fails leadership back");
  }

  /**
   * Given a preferred leader with auto-move enabled,
   * When its preference flips to standby (the anchor's locality moved),
   * Then it steps down once and the now-preferred candidate takes over (single controlled handoff).
   */
  @Test
  void autoMoveHandsOffWhenLeaderBecomesNonPreferred() {
    LeaseElector a = elector("a");
    LeaseElector b = elector("b");
    AtomicReference<Preference> aPref = new AtomicReference<>(Preference.PREFERRED);
    a.affinity(G, aPref::get, Duration.ZERO, true);
    b.affinity(G, () -> Preference.STANDBY, Duration.ZERO, false);

    a.campaign(G).block();
    b.campaign(G).block();
    a.tick();
    assertEquals("a", leader(a), "preferred a leads first");

    aPref.set(Preference.STANDBY); // the anchor moved away from a
    a.tick(); // renews, then auto-moves: a steps down
    assertEquals("none", leader(a), "a hands off (single controlled preemption)");

    b.tick();
    assertEquals("b", leader(b), "b takes over after the handoff");
  }

  // ---- Mode B: controller-driven promote / demote ----

  /**
   * Given two passive nodes,
   * When one is promoted and then the other,
   * Then the first wins and the second's cooperative promote fails (no preemption).
   */
  @Test
  void promoteIsCooperative() {
    LeaseElector a = elector("a");
    LeaseElector b = elector("b");

    assertTrue(a.promote(G).block(), "a promotes into a free group");
    assertEquals("a", leader(a));
    assertFalse(b.promote(G).block(), "b's cooperative promote does not preempt a's valid lease");
    assertEquals("a", leader(a));
  }

  /**
   * Given a promoted leader,
   * When it is demoted and another node is promoted,
   * Then leadership moves to the second node.
   */
  @Test
  void demoteReleasesAndAnotherCanPromote() {
    LeaseElector a = elector("a");
    LeaseElector b = elector("b");

    assertTrue(a.promote(G).block());
    a.demote(G).block();
    clock.addAndGet(10); // demote released the lease; b can take it immediately
    assertTrue(b.promote(G).block(), "after demote the group is free for b");
    assertEquals("b", leader(b));
  }

  /**
   * Given a promoted (controller-driven) node that then loses leadership,
   * When it ticks,
   * Then it stays passive — a promoted node never autonomously re-acquires (the controller decides).
   */
  @Test
  void promotedNodeDoesNotAutonomouslyReacquire() {
    LeaseElector a = elector("a");
    LeaseElector b = elector("b");

    assertTrue(a.promote(G).block()); // a leads
    clock.addAndGet(1500); // a's lease expires
    assertTrue(b.promote(G).block()); // b takes the now-free group
    assertEquals("b", leader(b));

    a.tick(); // a notices it lost leadership
    a.tick(); // and must NOT re-acquire (it only campaigned via promote)
    assertEquals("b", leader(b), "a stays passive; only the controller re-promotes");
  }
}
