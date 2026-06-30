package io.scalecube.prism.consensus;

import reactor.core.publisher.Mono;

/**
 * Sends a lease request to a remote acceptor and returns its reply. The transport binding (e.g.
 * over a scalecube/netty transport) implements this; tests provide an in-memory implementation that
 * can simulate partitions and loss.
 */
public interface PeerCaller {

  /**
   * Calls a peer acceptor.
   *
   * @param peer the peer address
   * @param request the request
   * @return the peer's reply (may error/timeout if unreachable)
   */
  Mono<LeaseResponse> call(String peer, LeaseRequest request);
}
