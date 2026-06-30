package io.scalecube.prism.examples;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.prism.Prism;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;

/**
 * Getting started — the smallest possible prism program. Build an ordinary scalecube cluster node,
 * wrap it with prism, and the service registry hangs off it. (The elector needs a
 * {@code PrismConfig} declaring a quorum — see {@code ObservabilityExample} /
 * {@code PrismGatewayExample}.)
 *
 * <p>prism <i>decorates</i> the cluster; {@code shutdown()} stops prism without taking ownership of
 * the cluster's own lifecycle.
 */
public final class HelloPrismExample {

  /**
   * Runs the example.
   *
   * @param args ignored
   */
  public static void main(String[] args) {
    // 1) An ordinary scalecube cluster node (single node here; no seeds).
    // 2) Wrap it with prism — one object to manage.
    Prism prism =
        new PrismImpl(new ClusterImpl().transportFactory(TcpTransportFactory::new)).startAwait();

    System.out.println("prism is up at " + prism.cluster().address());
    System.out.println("registry handle: " + prism.registry());

    // The AP service registry is ready immediately; register and look up locally.
    prism.registry().register("hello", java.util.Map.of("greeting", "world")).block();
    prism.registry().lookup("hello").forEach(e ->
        System.out.println("registered: " + e.service() + " -> " + e.properties()));

    prism.shutdown().block();
    System.out.println("prism is down");
  }
}
