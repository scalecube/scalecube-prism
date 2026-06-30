package io.scalecube.prism.examples;

import io.scalecube.prism.consensus.Acceptor;
import io.scalecube.prism.consensus.ConfigRecord;
import io.scalecube.prism.consensus.ConfigReplicator;
import io.scalecube.prism.consensus.LeaseRecord;
import io.scalecube.prism.consensus.LeaseRequest;
import io.scalecube.prism.consensus.LeaseTransfer;
import io.scalecube.prism.consensus.PeerCaller;
import io.scalecube.prism.consensus.QuorumConfig;
import io.scalecube.prism.consensus.QuorumConsensusStore;
import io.scalecube.prism.consensus.ReconfigurationManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * The self-electing / self-healing quorum (ADR-0015) in action. Starting from a single-node
 * bootstrap (C0 = {n0}), the leader-driven {@link ReconfigurationManager} grows the quorum to a
 * target size one member at a time, then — when a member dies — heals by replacing it. The
 * leader stays put throughout, and every step is a single-member change (the rule that keeps "never
 * two leaders" safe across reconfiguration).
 *
 * <p>In-process demo so it is fully self-contained; the same components run over the real transport
 * inside {@code PrismImpl} when you set {@code PrismConfig.withDynamicQuorum(target)}.
 */
public final class SelfElectingQuorumExample {

  private static final String GROUP = "gateway";
  private static final long TTL = 60_000L; // long, so the lease stays valid through the demo

  /**
   * Runs the example.
   *
   * @param args ignored
   */
  public static void main(String[] args) {
    final long now = 1000L;
    final List<String> pool = List.of("n0", "n1", "n2", "n3", "n4");
    final Map<String, Acceptor> acceptors = new HashMap<>();
    for (String n : pool) {
      acceptors.put(n, new Acceptor());
    }
    final PeerCaller caller = (peer, req) -> Mono.just(acceptors.get(peer).handle(req, now));

    // C0 = a single-node bootstrap; n0 is the leader.
    final QuorumConfig config = new QuorumConfig(List.of("n0"));
    final QuorumConsensusStore store =
        new QuorumConsensusStore("n0", config::members, acceptors.get("n0"), caller,
            () -> now, Duration.ofMillis(50));
    store.compareAndSet(GROUP, null, new LeaseRecord(GROUP, "n0", 1, now + TTL)); // n0 acquires

    final Set<String> live = new HashSet<>(pool);
    final ReconfigurationManager manager =
        new ReconfigurationManager(
            "n0", GROUP, config, store,
            memReplicator(),
            (member, hw) -> acceptors.get(member).handle(LeaseRequest.accept(hw), now).ok(),
            () -> live,
            () -> pool,
            3, // target quorum size
            () -> now);

    System.out.println("bootstrap:        config=" + config.members() + " leader=" + leader(store));

    System.out.println("\n-- self-formation: grow toward target 3, one member at a time --");
    for (int i = 0; i < 3; i++) {
      manager.tick();
      System.out.println("  after tick " + i + ":  config=" + config.members()
          + " (epoch " + config.epoch() + ") leader=" + leader(store));
    }

    System.out.println("\n-- self-heal: n1 dies; the quorum replaces it, one member at a time --");
    live.remove("n1");
    for (int i = 0; i < 3; i++) {
      manager.tick();
      System.out.println("  after tick " + i + ":  config=" + config.members()
          + " (epoch " + config.epoch() + ") leader=" + leader(store));
    }

    System.out.println("\nresult: healthy 3-node quorum without n1; n0 led throughout, "
        + "every change single-member.");
  }

  /** A trivial in-memory replicator: commits always succeed and remember the latest config. */
  private static ConfigReplicator memReplicator() {
    return new ConfigReplicator() {
      private ConfigRecord latest;

      @Override
      public boolean commit(ConfigRecord record, List<String> currentConfig) {
        latest = record;
        return true;
      }

      @Override
      public Optional<ConfigRecord> latestKnown() {
        return Optional.ofNullable(latest);
      }
    };
  }

  private static String leader(QuorumConsensusStore store) {
    return store.get(GROUP).map(LeaseRecord::owner).orElse("none");
  }
}
