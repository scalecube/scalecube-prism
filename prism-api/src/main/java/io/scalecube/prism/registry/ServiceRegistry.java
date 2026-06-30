package io.scalecube.prism.registry;

import io.scalecube.prism.version.FreshnessToken;
import java.util.Collection;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A fully-replicated, eventually-consistent service registry built on gossip. Every node holds the
 * complete catalog locally; each node is the sole writer of its own services.
 *
 * <p><b>Consistency contract.</b> The view is complete, convergent, and monotonic-per-key — but
 * <em>not</em> linearizable (unless a key is registered under {@link ConsistencyTier#CONSENSUS}).
 * Treat a lookup as a hint: try it, fall back if the instance is gone.
 *
 * <p><b>Write rule.</b> A node may only register/update/deregister its <em>own</em> services
 * (single-writer-per-key). Mutations to another member's entries are not permitted.
 */
public interface ServiceRegistry {

  /**
   * Advertise a service owned by the local member.
   *
   * @param service logical service name
   * @param properties initial properties
   * @param tier consistency tier for this service's data
   * @return completion when the registration has been accepted locally and queued for
   *     dissemination
   */
  Mono<Void> register(String service, Map<String, String> properties, ConsistencyTier tier);

  /**
   * Advertise a locally-owned service using the default {@link ConsistencyTier#CAUSAL} tier.
   *
   * @param service logical service name
   * @param properties initial properties
   * @return completion when accepted locally and queued for dissemination
   */
  default Mono<Void> register(String service, Map<String, String> properties) {
    return register(service, properties, ConsistencyTier.CAUSAL);
  }

  /**
   * Update a single property of a locally-owned service. Bumps the entry's version and
   * disseminates.
   */
  Mono<Void> update(String service, String key, String value);

  /** Gracefully remove a locally-owned service (emits a versioned tombstone). */
  Mono<Void> deregister(String service);

  /** Local lookup of all known instances of {@code service}. Always available, possibly stale. */
  Collection<ServiceEntry> lookup(String service);

  /**
   * Quorum read-repair lookup — the read-time mechanism behind {@link ConsistencyTier#QUORUM}.
   *
   * <p>Unlike {@link #lookup(String)} (a local, always-available, possibly-stale read), this fans
   * the query out to a <b>majority of the live members</b>, merges every reply with the local copy
   * by version (last-writer-wins), <b>repairs</b> the local store with anything newer it learns,
   * and then returns the freshest live instances. Because any two majorities intersect, the result
   * reflects any value a majority already holds — answering "is this current <em>before</em> I
   * route?" rather than "what did I last hear?".
   *
   * <p>This trades availability for freshness by design (CAP): if a majority cannot be reached
   * within the call budget the returned {@link Mono} <b>errors</b> with a
   * {@link QuorumUnavailableException} rather than answer from a possibly-stale local view. Use it
   * for the occasional must-be-fresh-at-read-time lookup; use {@link #lookup(String)} for the
   * common, cheap, always-available path.
   *
   * @param service logical service name
   * @return the freshest known live instances of {@code service}, after repairing the local view
   */
  default Mono<Collection<ServiceEntry>> lookupQuorum(String service) {
    // Implementations without a quorum read path degrade to the local view.
    return Mono.fromCallable(() -> lookup(service));
  }

  /** Local snapshot of the entire catalog. */
  Collection<ServiceEntry> list();

  /**
   * Stream of registry changes. A new subscriber first receives the current catalog as
   * {@code REGISTERED} events (a snapshot), then live changes (registered / updated /
   * deregistered / expired). This lets a late subscriber build a complete view from a single
   * subscription.
   */
  Flux<RegistryEvent> watch();

  /** Freshness handle for a given owner, to reason about staleness of reads. */
  FreshnessToken freshness(String ownerId);
}
