package io.scalecube.prism.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.transport.api.Transport;
import io.scalecube.cluster.transport.api.TransportConfig;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dynamic-quorum reconfiguration over a <b>real</b> scalecube transport (ADR-0015): the config
 * replicator disseminates a committed config to a majority and peers adopt it, and the §7.1 lease
 * transfer raises a joining acceptor's epoch floor — both end-to-end across three bound nodes.
 */
@DisplayName("Dynamic quorum over real transport: config replication + §7.1 state transfer")
class DynamicQuorumTransportIntegrationTest {

  private static final String GROUP = "sc/prism/quorum/control";
  private static final Duration TIMEOUT = Duration.ofSeconds(2);
  private final LongSupplier clock = System::currentTimeMillis;

  private Transport ta;
  private Transport tb;
  private Transport tc;
  private QuorumConfig ca;
  private QuorumConfig cb;
  private QuorumConfig cc;

  @BeforeEach
  void setup() {
    ta = bind();
    tb = bind();
    tc = bind();
    List<String> all = List.of(ta.address(), tb.address(), tc.address());
    ca = new QuorumConfig(all);
    cb = new QuorumConfig(all);
    cc = new QuorumConfig(all);
    QuorumNode.attachDynamic(ta, ca, clock, TIMEOUT, LeaseJournal.noop());
    QuorumNode.attachDynamic(tb, cb, clock, TIMEOUT, LeaseJournal.noop());
    QuorumNode.attachDynamic(tc, cc, clock, TIMEOUT, LeaseJournal.noop());
  }

  @AfterEach
  void teardown() {
    stop(ta);
    stop(tb);
    stop(tc);
  }

  /**
   * Given three dynamic-quorum nodes seeded with the same config at epoch 0,
   * When the leader's config replicator commits a single-member change at epoch 1,
   * Then a majority adopts it, peers' local configs advance to the new members at epoch 1, and the
   * leader's latestKnown reflects epoch 1.
   */
  @Test
  void configCommitDisseminatesAndPeersAdopt() {
    String a = ta.address();
    String b = tb.address();
    String c = tc.address();
    List<String> all = List.of(a, b, c);

    TransportConfigReplicator replicator =
        new TransportConfigReplicator(a, ta, ca, () -> all, GROUP, TIMEOUT);
    ConfigRecord next = new ConfigRecord(1, List.of(a, b)); // single-member: drop c

    boolean committed = replicator.commit(next, all);

    assertTrue(committed, "a majority of the current config must adopt the new config");
    assertEquals(1, cb.epoch(), "peer b adopts the committed config");
    // members are normalized to a sorted set by ConfigRecord/QuorumConfig, so compare unordered
    assertEquals(Set.of(a, b), Set.copyOf(cb.members()), "peer b adopts the new member set");
    assertEquals(1, cc.epoch(), "peer c adopts the committed config (pushed to the roster)");
    assertEquals(1, replicator.latestKnown().orElseThrow().epoch(), "latestKnown reflects epoch 1");
  }

  /**
   * Given three bound acceptors,
   * When the leader transfers the fencing high-water (epoch 5) to a joining member,
   * Then that member's acceptor holds it — so a later different owner must exceed epoch 5 (§7.1).
   */
  @Test
  void leaseTransferRaisesJoinerEpochFloor() {
    PeerCaller caller = new TransportPeerCaller(ta);
    TransportLeaseTransfer transfer = new TransportLeaseTransfer(caller, TIMEOUT);
    long now = clock.getAsLong();

    transfer.transfer(tb.address(), new LeaseRecord(GROUP, "owner-x", 5, now + 10_000));

    LeaseResponse onB = caller.call(tb.address(), LeaseRequest.get(GROUP)).block(TIMEOUT);
    assertTrue(onB != null && onB.currentLease().isPresent(), "b's acceptor must hold the lease");
    assertEquals(5, onB.currentLease().get().epoch(), "the fencing high-water reached b");
  }

  private static Transport bind() {
    return Transport.bindAwait(
        TransportConfig.defaultConfig().port(0).transportFactory(new TcpTransportFactory()));
  }

  private static void stop(Transport t) {
    if (t != null) {
      t.stop().block(Duration.ofSeconds(2));
    }
  }
}
