package io.scalecube.prism.examples;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.prism.Prism;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.util.Map;

/**
 * Watch the registry live from a consumer while a provider registers, drains (weight to 0), and
 * deregisters. The watch stream first replays a snapshot, then emits live changes.
 */
public final class RegistryWatchExample {

  /**
   * Runs the example.
   *
   * @param args ignored
   * @throws InterruptedException if interrupted while waiting for propagation
   */
  public static void main(String[] args) throws InterruptedException {
    Prism seed =
        new PrismImpl(new ClusterImpl().transportFactory(TcpTransportFactory::new)).startAwait();

    Prism consumer =
        new PrismImpl(
                new ClusterImpl()
                    .membership(opts -> opts.seedMembers(seed.cluster().address()))
                    .transportFactory(TcpTransportFactory::new))
            .startAwait();

    consumer
        .registry()
        .watch()
        .subscribe(
            ev ->
                System.out.printf(
                    "%-12s %s weight=%s%n",
                    ev.type(), ev.entry().service(), ev.entry().properties().get("weight")));

    Prism provider =
        new PrismImpl(
                new ClusterImpl()
                    .membership(opts -> opts.seedMembers(seed.cluster().address()))
                    .transportFactory(TcpTransportFactory::new))
            .startAwait();

    provider.registry().register("orders", Map.of("weight", "100")).block();
    Thread.sleep(1000);
    provider.registry().update("orders", "weight", "0").block(); // drain
    Thread.sleep(1000);
    provider.registry().deregister("orders").block();
    Thread.sleep(1000);

    seed.shutdown().block();
    provider.shutdown().block();
    consumer.shutdown().block();
  }
}
