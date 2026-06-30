package io.scalecube.prism.examples;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.prism.Prism;
import io.scalecube.prism.registry.ServiceEntry;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.util.Map;

/**
 * Advertise a service on one node and discover it from another. Discovery is eventually
 * consistent — the lookup is a local, always-available view that converges after a few gossip
 * rounds.
 */
public final class ServiceRegistryExample {

  /**
   * Runs the example.
   *
   * @param args ignored
   * @throws InterruptedException if interrupted while waiting for convergence
   */
  public static void main(String[] args) throws InterruptedException {
    Prism seed =
        new PrismImpl(new ClusterImpl().transportFactory(TcpTransportFactory::new)).startAwait();

    Prism provider =
        new PrismImpl(
                new ClusterImpl()
                    .membership(opts -> opts.seedMembers(seed.cluster().address()))
                    .transportFactory(TcpTransportFactory::new))
            .startAwait();
    provider.registry().register("orders", Map.of("weight", "100", "status", "passing")).block();

    Prism consumer =
        new PrismImpl(
                new ClusterImpl()
                    .membership(opts -> opts.seedMembers(seed.cluster().address()))
                    .transportFactory(TcpTransportFactory::new))
            .startAwait();

    Thread.sleep(2000); // let gossip converge

    for (ServiceEntry e : consumer.registry().lookup("orders")) {
      System.out.printf(
          "found %s @ %s weight=%s status=%s%n",
          e.service(), e.address(), e.properties().get("weight"), e.properties().get("status"));
    }

    seed.shutdown().block();
    provider.shutdown().block();
    consumer.shutdown().block();
  }
}
