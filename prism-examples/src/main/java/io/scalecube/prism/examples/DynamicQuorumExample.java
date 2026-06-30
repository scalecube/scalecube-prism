package io.scalecube.prism.examples;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.prism.Prism;
import io.scalecube.prism.elector.Leadership;
import io.scalecube.prism.runtime.PrismConfig;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The <b>self-electing (dynamic) quorum</b> (ADR-0015) and <b>durability</b> through the one-line
 * {@code Prism} API across three real nodes — the production shape of the in-process
 * {@code SelfElectingQuorumExample}.
 *
 * <p>Two features, both opt-in on {@link PrismConfig}:
 *
 * <ul>
 *   <li>{@code withDynamicQuorum(3)} — the quorum sizes and <b>heals itself</b>. Each node
 *       advertises
 *       its consensus address as cluster-gossip metadata, so the member roster is derived from the
 *       live cluster rather than hand-maintained; when a member dies, the leader replaces it by a
 *       single-member reconfiguration (the rule that keeps "never two leaders" safe).
 *   <li>{@code withPersistenceDir(dir)} — accepted leases, the HLC high-water, and the committed
 *       config chain are journaled (and compacted) under {@code dir}, so a node recovers its
 *       epoch and committed configuration across a restart instead of resetting.
 * </ul>
 *
 * <p>The demo elects one leader, then "crashes" it and shows the quorum self-heal to a new single
 * leader at a strictly higher fencing epoch.
 */
public final class DynamicQuorumExample {

  /**
   * Runs the example.
   *
   * @param args ignored
   * @throws Exception on host resolution, I/O, or interruption
   */
  public static void main(String[] args) throws Exception {
    String host = InetAddress.getLocalHost().getHostAddress();
    // The seed quorum C0; from here the quorum maintains itself from the gossip pool.
    List<String> seedQuorum = List.of(host + ":7101", host + ":7102", host + ":7103");

    Prism seed = node(host + ":7101", seedQuorum, null);
    Prism n2 = node(host + ":7102", seedQuorum, seed);
    Prism n3 = node(host + ":7103", seedQuorum, seed);
    List<Prism> all = List.of(seed, n2, n3);

    all.forEach(p -> p.elector().leadership("gateway").subscribe(l -> print(p, l)));
    all.forEach(p -> p.elector().campaign("gateway").subscribe());

    Thread.sleep(3000);
    Prism leader = leaderNode(all);
    System.out.println("elected leader: " + id(leader) + " — now crashing it…");

    leader.shutdown().block(); // crash the leader
    Thread.sleep(5000); // lease expires; survivors elect + the quorum self-heals the dropped member

    List<Prism> survivors = all.stream().filter(p -> p != leader).toList();
    System.out.println(
        "after self-heal, new leader: "
            + survivors.get(0).elector().currentLeader("gateway")
                .map(Object::toString).orElse("none"));

    survivors.forEach(p -> p.shutdown().block());
  }

  private static Prism node(String consensusAddress, List<String> seedQuorum, Prism clusterSeed)
      throws Exception {
    ClusterImpl cluster = new ClusterImpl().transportFactory(TcpTransportFactory::new);
    if (clusterSeed != null) {
      cluster = cluster.membership(opts -> opts.seedMembers(clusterSeed.cluster().address()));
    }
    Path persistence = Files.createTempDirectory("prism-" + consensusAddress.replace(':', '-'));
    PrismConfig config =
        new PrismConfig(consensusAddress, seedQuorum, TcpTransportFactory::new)
            .withDynamicQuorum(3) // self-sizing, self-healing quorum (ADR-0015)
            .withPersistenceDir(persistence); // durable leases + HLC + committed config chain
    return new PrismImpl(cluster, config).startAwait();
  }

  private static Prism leaderNode(List<Prism> nodes) {
    String leaderId =
        nodes.get(0).elector().currentLeader("gateway").map(m -> m.id()).orElseThrow();
    return nodes.stream().filter(p -> p.member().id().equals(leaderId)).findFirst().orElseThrow();
  }

  private static String id(Prism p) {
    return p.member().id();
  }

  private static void print(Prism node, Leadership lead) {
    System.out.printf(
        "[%s] %s active=%s epoch=%d%n",
        node.member().id(), lead.group(), lead.active(), lead.epoch());
  }
}
