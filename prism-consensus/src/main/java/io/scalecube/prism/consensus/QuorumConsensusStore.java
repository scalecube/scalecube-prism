package io.scalecube.prism.consensus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

/**
 * A distributed, partition-safe {@link ConsensusStore} backed by a fixed set of {@link Acceptor}s
 * (one per quorum member). A write succeeds only with a <b>majority</b> of acceptances; since any
 * two majorities intersect and an acceptor holds a single lease, two owners can never both be
 * majority-backed at once — that is "never two leaders". A stale believer is covered by the fencing
 * epoch.
 *
 * <p>This node's own {@link Acceptor} is consulted locally; the rest are reached via {@link
 * PeerCaller}. Unreachable peers (partition) simply don't count toward the majority, so a minority
 * partition cannot acquire — it loses availability, never safety.
 *
 * <p>{@code compareAndSet}'s {@code expected} is advisory: the authoritative decision is the
 * acceptor rule (epoch + expiry + majority).
 */
public final class QuorumConsensusStore implements ConsensusStore {

  private final String self;
  private final Supplier<List<String>> membership;
  private final Acceptor acceptor;
  private final PeerCaller caller;
  private final LongSupplier clock;
  private final Duration callTimeout;

  /**
   * Creates a quorum store over a <b>fixed</b> (static) member set.
   *
   * @param self this node's address
   * @param members all quorum member addresses (including self)
   * @param acceptor this node's acceptor
   * @param caller transport to reach other acceptors
   * @param clock physical time source in millis
   * @param callTimeout per-call timeout
   */
  public QuorumConsensusStore(
      String self,
      List<String> members,
      Acceptor acceptor,
      PeerCaller caller,
      LongSupplier clock,
      Duration callTimeout) {
    this(self, fixed(members), acceptor, caller, clock, callTimeout);
  }

  /**
   * Creates a quorum store over a <b>dynamic</b> member set. Every quorum read/write re-reads the
   * current membership from {@code membership}, so a committed reconfiguration takes effect on the
   * next operation (ADR-0015). Safety across a change relies on the membership source only ever
   * moving by single-member steps — see {@link QuorumConfig}.
   *
   * @param self this node's address
   * @param membership supplier of the current quorum member addresses (including self)
   * @param acceptor this node's acceptor
   * @param caller transport to reach other acceptors
   * @param clock physical time source in millis
   * @param callTimeout per-call timeout
   */
  public QuorumConsensusStore(
      String self,
      Supplier<List<String>> membership,
      Acceptor acceptor,
      PeerCaller caller,
      LongSupplier clock,
      Duration callTimeout) {
    this.self = Objects.requireNonNull(self, "self");
    this.membership = Objects.requireNonNull(membership, "membership");
    this.acceptor = Objects.requireNonNull(acceptor, "acceptor");
    this.caller = Objects.requireNonNull(caller, "caller");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.callTimeout = Objects.requireNonNull(callTimeout, "callTimeout");
  }

  private static Supplier<List<String>> fixed(List<String> members) {
    final List<String> copy = List.copyOf(Objects.requireNonNull(members, "members"));
    return () -> copy;
  }

  /** This node's acceptor, so a transport listener can route inbound requests to it. */
  public Acceptor acceptor() {
    return acceptor;
  }

  @Override
  public boolean compareAndSet(String group, LeaseRecord expected, LeaseRecord next) {
    if (next == null) {
      // The lease protocol has no "delete": a quorum lease is released by writing an expired
      // lease, not by clearing it. (Callers — the elector — release via an expired record.)
      throw new IllegalArgumentException(
          "quorum store cannot clear a lease (next=null); release via an expired lease");
    }
    final List<String> members = membership.get();
    long oks =
        collect(LeaseRequest.accept(next), members).stream().filter(LeaseResponse::ok).count();
    return oks >= majority(members);
  }

  @Override
  public Optional<LeaseRecord> get(String group) {
    // A quorum read returns the highest-epoch lease backed by a MAJORITY of acceptors — a
    // minority/local value must never be reported. Expiry is NOT filtered here: callers (the
    // elector) check expiry themselves, and the highest epoch (even if expired) is needed so a new
    // leader always picks a strictly greater fencing epoch than the previous one.
    final List<String> members = membership.get();
    final Map<String, Integer> counts = new HashMap<>();
    final Map<String, LeaseRecord> representative = new HashMap<>();
    for (LeaseResponse response : collect(LeaseRequest.get(group), members)) {
      response
          .currentLease()
          .ifPresent(
              lease -> {
                String key = lease.owner() + "#" + lease.epoch();
                counts.merge(key, 1, Integer::sum);
                representative.merge(
                    key, lease, (a, b) -> a.expiresAt() >= b.expiresAt() ? a : b);
              });
    }
    return counts.entrySet().stream()
        .filter(entry -> entry.getValue() >= majority(members))
        .map(entry -> representative.get(entry.getKey()))
        .max(Comparator.comparingLong(LeaseRecord::epoch));
  }

  @Override
  public PrepareResult prepare(String group, long ballot) {
    final List<String> members = membership.get();
    final List<LeaseResponse> responses = collect(LeaseRequest.prepare(group, ballot), members);
    long promisedCount = 0;
    long highestPromised = 0;
    LeaseRecord highestAccepted = null;
    for (LeaseResponse response : responses) {
      highestPromised = Math.max(highestPromised, response.promised());
      if (response.ok()) {
        promisedCount++;
      }
      if (response.currentLease().isPresent()
          && (highestAccepted == null
              || response.currentLease().get().epoch() > highestAccepted.epoch())) {
        highestAccepted = response.currentLease().get();
      }
    }
    return PrepareResult.of(promisedCount >= majority(members), highestAccepted, highestPromised);
  }

  private static int majority(List<String> members) {
    return members.size() / 2 + 1;
  }

  private List<LeaseResponse> collect(LeaseRequest request, List<String> members) {
    final long now = clock.getAsLong();
    final List<LeaseResponse> responses = new ArrayList<>();
    // The local acceptor counts toward the quorum ONLY when this node is a member of the current
    // config. A non-member is a pure Paxos proposer (it may drive consensus but does not vote): if
    // it counted its own acceptor it could manufacture a majority with only a MINORITY of real
    // members — a quorum-intersection violation that let a non-member win a lease and fork a
    // reconfiguration (surfaced by the partitioned real-reconfiguration fuzz). Membership can
    // exclude self while a node lags a reconfiguration or is reconfigured out, so check every call.
    if (members.contains(self)) {
      responses.add(acceptor.handle(request, now)); // self, locally
    }

    final List<String> remotes =
        members.stream().filter(m -> !m.equals(self)).collect(Collectors.toList());
    final List<LeaseResponse> remote =
        Flux.fromIterable(remotes)
            .flatMap(
                peer ->
                    caller
                        .call(peer, request)
                        .timeout(callTimeout)
                        .onErrorReturn(LeaseResponse.fail()))
            .collectList()
            .block(callTimeout.multipliedBy(2));
    if (remote != null) {
      responses.addAll(remote);
    }
    return responses;
  }
}
