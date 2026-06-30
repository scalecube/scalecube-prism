package io.scalecube.prism.elector.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.Member;
import io.scalecube.cluster.transport.api.Transport;
import io.scalecube.cluster.transport.api.TransportConfig;
import io.scalecube.cluster.utils.NetworkEmulatorTransport;
import io.scalecube.prism.consensus.QuorumNode;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

/**
 * End-to-end singleton election over a 3-member quorum on real (emulated) transports. Mirrors
 * scalecube's integration-test style: {@code NetworkEmulatorTransport} with partitioning, timed
 * awaits, and teardown.
 */
@DisplayName("Singleton elector: end-to-end over a quorum with partition")
class QuorumElectionIntegrationTest {

  private static final Duration TTL = Duration.ofSeconds(2);
  private static final Duration TICK = Duration.ofMillis(300);
  private static final Duration CALL_TIMEOUT = Duration.ofMillis(500);

  private final Set<NetworkEmulatorTransport> transports = new HashSet<>();
  private LeaseElector e1;
  private LeaseElector e2;

  // observed leadership state
  private final Set<String> active = new HashSet<>();
  private int maxConcurrentActive;

  @AfterEach
  void tearDown() {
    if (e1 != null) {
      e1.stop();
    }
    if (e2 != null) {
      e2.stop();
    }
    transports.forEach(this::destroy);
  }

  /**
   * Given two candidates campaigning over a 3-member quorum on real transports,
   * When one Active is elected and then partitioned away from the quorum,
   * Then it steps down, the connected standby takes over, and never two are Active at once.
   */
  @Test
  void exactlyOneActiveAndSafeFailoverOnPartition() {
    NetworkEmulatorTransport t1 = createTransport();
    NetworkEmulatorTransport t2 = createTransport();
    NetworkEmulatorTransport t3 = createTransport();
    List<String> members = List.of(t1.address(), t2.address(), t3.address());

    QuorumNode q1 = QuorumNode.attach(t1, members, System::currentTimeMillis, CALL_TIMEOUT);
    QuorumNode q2 = QuorumNode.attach(t2, members, System::currentTimeMillis, CALL_TIMEOUT);
    QuorumNode.attach(t3, members, System::currentTimeMillis, CALL_TIMEOUT); // acceptor only

    Member m1 = new Member("gw-1", null, t1.address(), "prism");
    Member m2 = new Member("gw-2", null, t2.address(), "prism");
    e1 = new LeaseElector(m1, q1.store(), id -> Optional.empty(), TTL, System::currentTimeMillis);
    e2 = new LeaseElector(m2, q2.store(), id -> Optional.empty(), TTL, System::currentTimeMillis);

    e1.leadership("gateway").subscribe(this::onLeadership);
    e2.leadership("gateway").subscribe(this::onLeadership);

    e1.campaign("gateway").block();
    e2.campaign("gateway").block();
    e1.start(TICK);
    e2.start(TICK);

    awaitSeconds(2);
    assertEquals(1, active.size(), "exactly one Active after election");
    final String firstLeader = active.iterator().next();

    // Partition the current leader's node away from the quorum.
    NetworkEmulatorTransport leaderTransport = firstLeader.equals("gw-1") ? t1 : t2;
    leaderTransport.networkEmulator().blockAllOutbound();
    leaderTransport.networkEmulator().blockAllInbound();

    awaitSeconds(5); // the lease expires; the connected standby (with t3) takes over

    assertFalse(active.contains(firstLeader), "partitioned leader must step down");
    assertEquals(1, active.size(), "exactly one Active after failover");
    assertEquals(1, maxConcurrentActive, "never two Active at any time");
  }

  private synchronized void onLeadership(io.scalecube.prism.elector.Leadership lead) {
    if (lead.active()) {
      active.add(lead.member().id());
    } else {
      active.remove(lead.member().id());
    }
    maxConcurrentActive = Math.max(maxConcurrentActive, active.size());
  }

  // ---- scalecube-style helpers ----

  private NetworkEmulatorTransport createTransport() {
    NetworkEmulatorTransport transport =
        new NetworkEmulatorTransport(
            Transport.bindAwait(
                TransportConfig.defaultConfig().transportFactory(new TcpTransportFactory())));
    transports.add(transport);
    return transport;
  }

  private void destroy(Transport transport) {
    if (transport == null || transport.isStopped()) {
      return;
    }
    try {
      transport.stop().block(Duration.ofSeconds(1));
    } catch (Exception ignore) {
      // no-op
    }
  }

  private static void awaitSeconds(long seconds) {
    try {
      TimeUnit.SECONDS.sleep(seconds);
    } catch (InterruptedException e) {
      throw Exceptions.propagate(e);
    }
  }
}
