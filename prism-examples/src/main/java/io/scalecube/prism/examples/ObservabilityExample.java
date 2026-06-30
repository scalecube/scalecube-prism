package io.scalecube.prism.examples;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.prism.Prism;
import io.scalecube.prism.observability.InMemoryMetrics;
import io.scalecube.prism.runtime.PrismConfig;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.net.InetAddress;
import java.util.List;

/**
 * Wiring metrics into prism. A {@link InMemoryMetrics} sink (the same {@code Metrics} SPI a
 * Micrometer/OpenTelemetry adapter would implement) is passed to the runtime; after an election its
 * counters reflect what happened. Here a single-node quorum elects this node and the
 * {@code prism.elector.granted} counter increments.
 */
public final class ObservabilityExample {

  /**
   * Runs the example.
   *
   * @param args ignored
   * @throws Exception on host resolution or interruption
   */
  public static void main(String[] args) throws Exception {
    String host = InetAddress.getLocalHost().getHostAddress();
    String address = host + ":7101";

    InMemoryMetrics metrics = new InMemoryMetrics();
    PrismConfig config = new PrismConfig(address, List.of(address), TcpTransportFactory::new);
    Prism prism =
        new PrismImpl(
                new ClusterImpl().transportFactory(TcpTransportFactory::new), config, metrics)
            .startAwait();

    prism.elector().campaign("gateway").block();
    Thread.sleep(1500); // let the elector tick and acquire the lease

    System.out.println("leader now      = "
        + prism.elector().currentLeader("gateway").map(Object::toString).orElse("none"));
    System.out.println("elector.granted = " + metrics.count("prism.elector.granted"));
    System.out.println("elector.revoked = " + metrics.count("prism.elector.revoked"));

    prism.shutdown().block();
  }
}
