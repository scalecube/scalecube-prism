package io.scalecube.prism.examples;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.prism.Prism;
import io.scalecube.prism.registry.ConsistencyTier;
import io.scalecube.prism.registry.ServiceEntry;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.prism.version.FreshnessToken;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.util.Map;

/**
 * The consistency dial and freshness tokens. The owner of a key declares the weakest tier that is
 * still correct: {@code EVENTUAL} (pure gossip LWW) for coarse data, {@code CAUSAL} (the default,
 * with session guarantees) for properties that must respect read-your-writes / monotonic reads.
 *
 * <p>A {@link FreshnessToken} for an owner advances monotonically as that owner's writes are seen,
 * so a reader can assert it has observed at least up to a given version (the basis of sessions).
 */
public final class ConsistencyTiersExample {

  /**
   * Runs the example.
   *
   * @param args ignored
   * @throws InterruptedException if interrupted
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

    // Coarse, stale-tolerant data: cheapest tier.
    provider.registry().register("cache", Map.of("region", "eu"), ConsistencyTier.EVENTUAL).block();
    // Properties that must respect causality: the default CAUSAL tier.
    provider.registry().register("orders", Map.of("weight", "100"), ConsistencyTier.CAUSAL).block();

    String owner = provider.registry().lookup("orders").iterator().next().owner();
    final FreshnessToken before = provider.registry().freshness(owner);

    provider.registry().update("orders", "weight", "150").block();
    FreshnessToken after = provider.registry().freshness(owner);

    ServiceEntry orders = provider.registry().lookup("orders").iterator().next();
    System.out.println("orders tier      = " + orders.tier());
    System.out.println("orders version   = " + orders.version());
    System.out.println("freshness before = " + before.upTo());
    System.out.println("freshness after  = " + after.upTo());
    System.out.println(
        "monotonic?       = " + (before.upTo().compareTo(after.upTo()) <= 0)); // read-your-writes

    seed.shutdown().block();
    provider.shutdown().block();
  }
}
