package io.scalecube.prism.examples;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.prism.Prism;
import io.scalecube.prism.registry.ServiceEntry;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;

/**
 * Discovering many instances of a service and picking one. Two providers advertise the same service
 * name with different properties; a consumer discovers both and selects a healthy instance by
 * weight — the kind of client-side, load-aware routing a registry exists to enable.
 *
 * <p>{@code lookup} returns every alive instance (one per owner); selection policy is the caller's.
 */
public final class InstanceSelectionExample {

  /**
   * Runs the example.
   *
   * @param args ignored
   * @throws InterruptedException if interrupted while waiting for convergence
   */
  public static void main(String[] args) throws InterruptedException {
    Prism seed =
        new PrismImpl(new ClusterImpl().transportFactory(TcpTransportFactory::new)).startAwait();
    seed.registry().register("orders", Map.of("weight", "100", "status", "passing")).block();

    Prism providerB =
        new PrismImpl(
                new ClusterImpl()
                    .membership(opts -> opts.seedMembers(seed.cluster().address()))
                    .transportFactory(TcpTransportFactory::new))
            .startAwait();
    providerB.registry().register("orders", Map.of("weight", "50", "status", "passing")).block();

    Prism consumer =
        new PrismImpl(
                new ClusterImpl()
                    .membership(opts -> opts.seedMembers(seed.cluster().address()))
                    .transportFactory(TcpTransportFactory::new))
            .startAwait();

    Thread.sleep(2000); // let gossip converge

    Collection<ServiceEntry> instances = consumer.registry().lookup("orders");
    System.out.println("discovered " + instances.size() + " instance(s) of 'orders':");
    instances.forEach(e -> System.out.printf("  %s weight=%s status=%s%n",
        e.address(), e.properties().get("weight"), e.properties().get("status")));

    ServiceEntry chosen =
        instances.stream()
            .filter(ServiceEntry::alive)
            .filter(e -> "passing".equals(e.properties().get("status")))
            .max(Comparator.comparingInt(
                e -> Integer.parseInt(e.properties().getOrDefault("weight", "0"))))
            .orElse(null);
    System.out.println("chosen (highest-weight healthy) = "
        + (chosen == null ? "none" : chosen.address()));

    seed.shutdown().block();
    providerB.shutdown().block();
    consumer.shutdown().block();
  }
}
