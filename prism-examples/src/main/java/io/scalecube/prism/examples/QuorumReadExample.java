package io.scalecube.prism.examples;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.prism.Prism;
import io.scalecube.prism.registry.ConsistencyTier;
import io.scalecube.prism.registry.QuorumUnavailableException;
import io.scalecube.prism.registry.ServiceEntry;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.util.Collection;
import java.util.Map;

/**
 * The {@code QUORUM} tier of the consistency dial: a fresh-at-read-time lookup. Where {@link
 * io.scalecube.prism.registry.ServiceRegistry#lookup(String) lookup} returns the local (possibly
 * stale) view with no network hop, {@link
 * io.scalecube.prism.registry.ServiceRegistry#lookupQuorum(String) lookupQuorum} fans the query out
 * to a <b>majority</b> of members, merges every reply last-writer-wins, repairs the local store,
 * and returns the freshest instances — so it answers "is this current right now, before I route?".
 *
 * <p>It trades availability for freshness by design (CAP): if a majority cannot be reached it
 * errors with {@link QuorumUnavailableException} rather than answer from a stale local view. This
 * example
 * registers a service on one node, updates it, and shows a separate consumer reading the freshest
 * value through a quorum read.
 */
public final class QuorumReadExample {

  /**
   * Runs the example.
   *
   * @param args ignored
   * @throws InterruptedException if interrupted while waiting for the cluster to form
   */
  public static void main(String[] args) throws InterruptedException {
    Prism seed =
        new PrismImpl(new ClusterImpl().transportFactory(TcpTransportFactory::new)).startAwait();

    Prism provider = node(seed);
    Prism consumer = node(seed);

    // Tag the key QUORUM to signal it warrants fresh reads; the reader opts in via lookupQuorum.
    provider
        .registry()
        .register("orders", Map.of("weight", "100"), ConsistencyTier.QUORUM)
        .block();

    Thread.sleep(1500); // let membership form so a majority is reachable

    // A quorum read: ask a majority, repair locally, return the freshest. Reflects the latest write
    // even if this node's gossip copy hasn't caught up.
    Collection<ServiceEntry> fresh = consumer.registry().lookupQuorum("orders").block();
    print("quorum read after register", fresh);

    // Update at the provider, then read again through the quorum — the new value is visible.
    provider.registry().update("orders", "weight", "250").block();
    fresh = consumer.registry().lookupQuorum("orders").block();
    print("quorum read after update", fresh);

    seed.shutdown().block();
    provider.shutdown().block();
    consumer.shutdown().block();
  }

  private static void print(String label, Collection<ServiceEntry> entries) {
    if (entries == null || entries.isEmpty()) {
      System.out.printf("%s: (none)%n", label);
      return;
    }
    for (ServiceEntry e : entries) {
      System.out.printf("%s: %s @ %s weight=%s%n",
          label, e.service(), e.address(), e.properties().get("weight"));
    }
  }

  private static Prism node(Prism seed) {
    return new PrismImpl(
            new ClusterImpl()
                .membership(opts -> opts.seedMembers(seed.cluster().address()))
                .transportFactory(TcpTransportFactory::new))
        .startAwait();
  }

  private QuorumReadExample() {}
}
