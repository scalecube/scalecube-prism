package io.scalecube.prism;

import io.scalecube.cluster.Cluster;
import io.scalecube.cluster.Member;
import io.scalecube.prism.elector.SingletonElector;
import io.scalecube.prism.registry.ServiceRegistry;
import reactor.core.publisher.Mono;

/**
 * Entry point: a thin layer over a scalecube {@link Cluster}. Build a normal cluster node, then
 * wrap it with prism to obtain a {@link ServiceRegistry} and a {@link SingletonElector}.
 *
 * <pre>{@code
 * Cluster cluster = new ClusterImpl().transportFactory(TcpTransportFactory::new).startAwait();
 * Prism prism = new PrismImpl(cluster).startAwait();
 *
 * ServiceRegistry registry = prism.registry();
 * SingletonElector elector = prism.elector();
 * }</pre>
 *
 * <p>prism decorates the cluster; it does not own its lifecycle. {@link #shutdown()} stops prism's
 * own components and leaves the underlying {@link Cluster} for the caller to manage.
 */
public interface Prism {

  /**
   * Starts prism's components.
   *
   * @return this prism, once started
   */
  Mono<Prism> start();

  /**
   * Blocking convenience for {@link #start()}.
   *
   * @return this prism, once started
   */
  Prism startAwait();

  /**
   * The service registry view.
   *
   * @return the registry
   */
  ServiceRegistry registry();

  /**
   * The singleton elector.
   *
   * @return the elector
   */
  SingletonElector elector();

  /**
   * The underlying scalecube cluster this prism decorates.
   *
   * @return the cluster
   */
  Cluster cluster();

  /**
   * The local member.
   *
   * @return the local member
   */
  Member member();

  /**
   * Stops prism's components. Does not stop the underlying {@link Cluster}.
   *
   * @return completion when prism has stopped
   */
  Mono<Void> shutdown();
}
