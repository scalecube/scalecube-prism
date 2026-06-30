package io.scalecube.prism.examples;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.cluster.Member;
import io.scalecube.prism.Prism;
import io.scalecube.prism.elector.Preference;
import io.scalecube.prism.runtime.PrismConfig;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

/**
 * Leader affinity (ADR-0016) over a <b>real 3-node cluster</b> — production-shaped: each node is a
 * {@code Prism} with a configured quorum, and the elector renews/promotes on its own timer (no
 * manual ticking). Two modes, both via the public {@code prism.elector()} API:
 *
 * <ul>
 *   <li><b>Mode A (autonomous):</b> each node declares a {@link Preference}; the {@code PREFERRED}
 *       node wins the {@code gateway} election even if a {@code STANDBY} campaigned first, and
 *       never fails leadership back when a preferred node returns.
 *   <li><b>Mode B (controller-driven):</b> for the {@code scheduler} group nobody campaigns; an
 *       external controller drives leadership with cooperative {@code promote}/{@code demote}.
 * </ul>
 */
public final class LeaderAffinityExample {

  private static final String GATEWAY = "gateway";
  private static final String SCHEDULER = "scheduler";

  /**
   * Runs the example.
   *
   * @param args ignored
   * @throws Exception on host resolution or interruption
   */
  public static void main(String[] args) throws Exception {
    String host = InetAddress.getLocalHost().getHostAddress();
    List<String> quorum = List.of(host + ":7001", host + ":7002", host + ":7003");

    Prism node1 = node(host + ":7001", quorum, null);
    Prism node2 = node(host + ":7002", quorum, node1);
    Prism node3 = node(host + ":7003", quorum, node1);

    // ---- Mode A: preference-biased autonomous election for the "gateway" group ----
    // node2 sits in the same zone as the anchor, so it is PREFERRED; the others are STANDBY.
    // A STANDBY waits `yieldWindow` before contending, giving the PREFERRED node time to win.
    Duration yieldWindow = Duration.ofSeconds(2);
    node1.elector().affinity(GATEWAY, () -> Preference.STANDBY, yieldWindow, true);
    node2.elector().affinity(GATEWAY, () -> Preference.PREFERRED, yieldWindow, true);
    node3.elector().affinity(GATEWAY, () -> Preference.STANDBY, yieldWindow, true);

    node1.elector().campaign(GATEWAY).block();
    node2.elector().campaign(GATEWAY).block();
    node3.elector().campaign(GATEWAY).block();

    Thread.sleep(4000); // the electors run their own timers; the PREFERRED node wins
    System.out.println("Mode A — gateway leader = " + leaderId(node2, GATEWAY)
        + "  (preferred node2 wins, regardless of who campaigned first)");

    // ---- Mode B: controller-driven promote/demote for the "scheduler" group ----
    // Nobody campaigns; a controller decides. promote is cooperative (no preemption).
    boolean won1 = node1.elector().promote(SCHEDULER).block();
    System.out.println("Mode B — promote node1: won=" + won1
        + " leader=" + leaderId(node1, SCHEDULER));
    boolean won3 = node3.elector().promote(SCHEDULER).block();
    System.out.println("Mode B — promote node3: won=" + won3
        + " leader=" + leaderId(node1, SCHEDULER) + "  (cooperative: node1 not preempted)");

    node1.elector().demote(SCHEDULER).block();
    Thread.sleep(1000);
    boolean won3b = node3.elector().promote(SCHEDULER).block();
    System.out.println("Mode B — demote node1, promote node3: won=" + won3b
        + " leader=" + leaderId(node3, SCHEDULER));

    node1.shutdown().block();
    node2.shutdown().block();
    node3.shutdown().block();
  }

  /** Builds one node: an ordinary scalecube cluster wrapped by prism with a configured quorum. */
  private static Prism node(String consensusAddress, List<String> quorum, Prism seed) {
    ClusterImpl cluster = new ClusterImpl().transportFactory(TcpTransportFactory::new);
    if (seed != null) {
      cluster = cluster.membership(opts -> opts.seedMembers(seed.cluster().address()));
    }
    PrismConfig config = new PrismConfig(consensusAddress, quorum, TcpTransportFactory::new);
    return new PrismImpl(cluster, config).startAwait();
  }

  private static String leaderId(Prism node, String group) {
    return node.elector().currentLeader(group).map(Member::id).orElse("none");
  }
}
