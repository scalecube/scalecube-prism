package io.scalecube.prism.examples;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.prism.Prism;
import io.scalecube.prism.elector.Leadership;
import io.scalecube.prism.runtime.PrismConfig;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.net.InetAddress;
import java.util.List;

/**
 * Active/passive gateway through the one-line {@code Prism} API across three real nodes. Two nodes
 * campaign for the {@code gateway} group; the configured 3-member quorum guarantees exactly one
 * Active.
 *
 * <p>The consensus-transport address must equal what the transport actually advertises, so the
 * quorum list is built from the resolved host plus fixed ports.
 */
public final class PrismGatewayExample {

  /**
   * Runs the example.
   *
   * @param args ignored
   * @throws Exception on host resolution or interruption
   */
  public static void main(String[] args) throws Exception {
    String host = InetAddress.getLocalHost().getHostAddress();
    List<String> quorum =
        List.of(host + ":7001", host + ":7002", host + ":7003");

    Prism seed = node(host + ":7001", quorum, null);
    Prism n2 = node(host + ":7002", quorum, seed);
    Prism n3 = node(host + ":7003", quorum, seed);

    n2.elector().leadership("gateway").subscribe(lead -> print("n2", lead));
    n3.elector().leadership("gateway").subscribe(lead -> print("n3", lead));

    n2.elector().campaign("gateway").block();
    n3.elector().campaign("gateway").block();

    Thread.sleep(3000);
    System.out.println(
        "current gateway leader: "
            + n2.elector().currentLeader("gateway").map(Object::toString).orElse("none"));

    seed.shutdown().block();
    n2.shutdown().block();
    n3.shutdown().block();
  }

  private static Prism node(String consensusAddress, List<String> quorum, Prism seed) {
    ClusterImpl cluster = new ClusterImpl().transportFactory(TcpTransportFactory::new);
    if (seed != null) {
      cluster = cluster.membership(opts -> opts.seedMembers(seed.cluster().address()));
    }
    PrismConfig config = new PrismConfig(consensusAddress, quorum, TcpTransportFactory::new);
    return new PrismImpl(cluster, config).startAwait();
  }

  private static void print(String node, Leadership lead) {
    System.out.printf(
        "[%s] %s active=%s epoch=%d%n", node, lead.group(), lead.active(), lead.epoch());
  }
}
