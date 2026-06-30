package io.scalecube.prism.consensus;

import io.scalecube.cluster.transport.api.Message;
import io.scalecube.cluster.transport.api.Transport;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** {@link PeerCaller} over a scalecube {@link Transport} (request/response to a peer acceptor). */
public final class TransportPeerCaller implements PeerCaller {

  private final Transport transport;

  public TransportPeerCaller(Transport transport) {
    this.transport = transport;
  }

  @Override
  public Mono<LeaseResponse> call(String peer, LeaseRequest request) {
    Message message =
        Message.withData(LeaseCodec.encode(request))
            .qualifier(QuorumNode.REQUEST_QUALIFIER)
            .correlationId(UUID.randomUUID().toString())
            .sender(transport.address())
            .build();
    return transport.requestResponse(peer, message).map(m -> LeaseCodec.decodeResponse(m.data()));
  }
}
