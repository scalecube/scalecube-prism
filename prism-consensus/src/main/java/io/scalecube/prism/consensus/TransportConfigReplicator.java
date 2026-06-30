package io.scalecube.prism.consensus;

import io.scalecube.cluster.transport.api.Message;
import io.scalecube.cluster.transport.api.Transport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;

/**
 * {@link ConfigReplicator} over a scalecube {@link Transport} (ADR-0015). The leader's
 * {@link #commit} pushes a {@code PROPOSE} to every roster member and reports whether a
 * <b>majority of the current config</b> adopted it (self counts — the leader commits locally
 * after). {@link #latestKnown} reads peers' configs and returns the highest, so a freshly elected
 * or lagging node converges to the latest committed configuration.
 *
 * <p>The single proposer is always the current leader, so a PROPOSE never races another.
 */
public final class TransportConfigReplicator implements ConfigReplicator {

  private final String self;
  private final Transport transport;
  private final QuorumConfig localConfig;
  private final Supplier<List<String>> roster;
  private final String group;
  private final Duration timeout;

  /**
   * Creates a transport-backed config replicator.
   *
   * @param self this node's consensus address
   * @param transport the consensus transport
   * @param localConfig this node's local committed configuration
   * @param roster supplier of the candidate roster (dissemination/discovery scope)
   * @param group the election/consensus group this quorum serves
   * @param timeout per-peer config RPC timeout
   */
  public TransportConfigReplicator(
      String self,
      Transport transport,
      QuorumConfig localConfig,
      Supplier<List<String>> roster,
      String group,
      Duration timeout) {
    this.self = Objects.requireNonNull(self, "self");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.localConfig = Objects.requireNonNull(localConfig, "localConfig");
    this.roster = Objects.requireNonNull(roster, "roster");
    this.group = Objects.requireNonNull(group, "group");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public boolean commit(ConfigRecord record, List<String> currentConfig) {
    final Set<String> targets = new HashSet<>(roster.get());
    targets.addAll(currentConfig);
    targets.remove(self);

    final ConfigRequest propose = ConfigRequest.propose(group, record);
    final Set<String> accepted = new HashSet<>();
    final List<String> peers = new ArrayList<>(targets);
    final List<String[]> results =
        Flux.fromIterable(peers)
            .flatMap(
                peer ->
                    call(peer, propose)
                        .map(resp -> new String[] {peer, Boolean.toString(resp.accepted())}))
            .collectList()
            .block(timeout.multipliedBy(2));
    if (results != null) {
      for (String[] r : results) {
        if (Boolean.parseBoolean(r[1])) {
          accepted.add(r[0]);
        }
      }
    }

    int acks = 1; // self — the leader adopts locally after a successful commit
    for (String member : currentConfig) {
      if (!member.equals(self) && accepted.contains(member)) {
        acks++;
      }
    }
    return acks >= currentConfig.size() / 2 + 1;
  }

  @Override
  public Optional<ConfigRecord> latestKnown() {
    ConfigRecord best = new ConfigRecord(localConfig.epoch(), localConfig.members());
    final List<String> peers = new ArrayList<>(roster.get());
    peers.remove(self);
    final ConfigRequest get = ConfigRequest.get(group);
    final List<ConfigRecord> known =
        Flux.fromIterable(peers)
            .flatMap(peer -> call(peer, get).mapNotNull(r -> r.latest().orElse(null)))
            .collectList()
            .block(timeout.multipliedBy(2));
    if (known != null) {
      best =
          Flux.fromIterable(known)
              .concatWithValues(best)
              .reduce((a, b) -> a.epoch() >= b.epoch() ? a : b)
              .block();
    }
    return Optional.ofNullable(best);
  }

  private reactor.core.publisher.Mono<ConfigResponse> call(String peer, ConfigRequest request) {
    final Message message =
        Message.withData(ConfigCodec.encode(request))
            .qualifier(QuorumNode.CONFIG_REQUEST_QUALIFIER)
            .correlationId(UUID.randomUUID().toString())
            .sender(transport.address())
            .build();
    return transport
        .requestResponse(peer, message)
        .map(m -> ConfigCodec.decodeResponse(m.data()))
        .timeout(timeout)
        .onErrorReturn(ConfigResponse.fail());
  }
}
