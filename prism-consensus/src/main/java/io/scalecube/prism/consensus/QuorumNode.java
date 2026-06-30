package io.scalecube.prism.consensus;

import io.scalecube.cluster.transport.api.Message;
import io.scalecube.cluster.transport.api.Transport;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;
import reactor.core.Disposable;

/**
 * One quorum member: binds an {@link Acceptor} to a scalecube {@link Transport} (answering peers'
 * lease requests) and exposes a {@link QuorumConsensusStore} for the local elector to drive.
 *
 * <p>Attach to an already-bound transport; the {@code members} list is the configured static quorum
 * (their transport addresses, including this one).
 */
public final class QuorumNode {

  /** Transport qualifier for inbound lease requests (proposer → acceptor). */
  public static final String REQUEST_QUALIFIER = "sc/prism/consensus/lease/req";
  /** Transport qualifier for lease replies (acceptor → proposer). */
  public static final String RESPONSE_QUALIFIER = "sc/prism/consensus/lease/resp";
  /** Transport qualifier for inbound reconfiguration requests (leader → member). */
  public static final String CONFIG_REQUEST_QUALIFIER = "sc/prism/consensus/config/req";
  /** Transport qualifier for reconfiguration replies (member → leader). */
  public static final String CONFIG_RESPONSE_QUALIFIER = "sc/prism/consensus/config/resp";

  private final Transport transport;
  private final QuorumConsensusStore store;
  private final QuorumConfig config; // nullable — present only for a dynamic-quorum node
  private final Disposable subscription;

  private QuorumNode(
      Transport transport,
      QuorumConsensusStore store,
      QuorumConfig config,
      Disposable subscription) {
    this.transport = transport;
    this.store = store;
    this.config = config;
    this.subscription = subscription;
  }

  /**
   * Attaches the acceptor + store to a bound transport and starts answering peer requests.
   *
   * @param transport this node's (already started) consensus transport
   * @param members all quorum member addresses (including this one)
   * @param clock physical time source in millis
   * @param callTimeout per-call timeout for peer requests
   * @return the attached node
   */
  public static QuorumNode attach(
      Transport transport, List<String> members, LongSupplier clock, Duration callTimeout) {
    return attach(transport, members, clock, callTimeout, LeaseJournal.noop());
  }

  /**
   * Attaches with a durable acceptor (crash-safe). Recovers accepted leases from {@code journal}.
   *
   * @param transport this node's (already started) consensus transport
   * @param members all quorum member addresses (including this one)
   * @param clock physical time source in millis
   * @param callTimeout per-call timeout for peer requests
   * @param journal durable lease storage for the acceptor
   * @return the attached node
   */
  public static QuorumNode attach(
      Transport transport,
      List<String> members,
      LongSupplier clock,
      Duration callTimeout,
      LeaseJournal journal) {
    return attach(transport, transport.address(), members, clock, callTimeout, journal);
  }

  /**
   * As {@link #attach(Transport, List, LongSupplier, Duration, LeaseJournal)} but with an explicit
   * {@code self} identity. {@code self} MUST be in the same namespace as {@code members} (the
   * configured/advertised consensus address), so the store correctly recognizes when this node is a
   * member and counts its local acceptor only then. A node whose {@code transport.address()} does
   * not match its configured address would otherwise never count itself (or, if genuinely absent
   * from members, wrongly count itself).
   *
   * @param transport this node's (already started) consensus transport
   * @param self this node's address in the {@code members} namespace
   * @param members all quorum member addresses (including this one)
   * @param clock physical time source in millis
   * @param callTimeout per-call timeout for peer requests
   * @param journal durable lease storage for the acceptor
   * @return the attached node
   */
  public static QuorumNode attach(
      Transport transport,
      String self,
      List<String> members,
      LongSupplier clock,
      Duration callTimeout,
      LeaseJournal journal) {
    final Acceptor acceptor = new Acceptor(journal);
    final QuorumConsensusStore store =
        new QuorumConsensusStore(
            self, members, acceptor, new TransportPeerCaller(transport), clock, callTimeout);

    final Disposable subscription =
        transport
            .listen()
            .filter(message -> REQUEST_QUALIFIER.equals(message.qualifier()))
            .subscribe(message -> answer(transport, acceptor, clock, message));

    return new QuorumNode(transport, store, null, subscription);
  }

  /**
   * Attaches a <b>dynamic-quorum</b> node (ADR-0015): the store reads its membership from a live
   * {@link QuorumConfig}, and the node additionally answers reconfiguration requests — adopting any
   * higher-epoch config a leader disseminates and replying with its latest known config.
   *
   * @param transport this node's (already started) consensus transport
   * @param config the node's local committed configuration (seeded with the candidate roster C0)
   * @param clock physical time source in millis
   * @param callTimeout per-call timeout for peer requests
   * @param journal durable lease storage for the acceptor
   * @return the attached node (its {@link #config()} is the live configuration)
   */
  public static QuorumNode attachDynamic(
      Transport transport,
      QuorumConfig config,
      LongSupplier clock,
      Duration callTimeout,
      LeaseJournal journal) {
    return attachDynamic(transport, transport.address(), config, clock, callTimeout, journal);
  }

  /**
   * As {@link #attachDynamic(Transport, QuorumConfig, LongSupplier, Duration, LeaseJournal)} but
   * an explicit {@code self} identity in the same namespace as the config's members (see {@link
   * #attach(Transport, String, List, LongSupplier, Duration, LeaseJournal)} for why this matters).
   *
   * @param transport this node's (already started) consensus transport
   * @param self this node's address in the config-members namespace
   * @param config the node's local committed configuration (seeded with the candidate roster C0)
   * @param clock physical time source in millis
   * @param callTimeout per-call timeout for peer requests
   * @param journal durable lease storage for the acceptor
   * @return the attached node (its {@link #config()} is the live configuration)
   */
  public static QuorumNode attachDynamic(
      Transport transport,
      String self,
      QuorumConfig config,
      LongSupplier clock,
      Duration callTimeout,
      LeaseJournal journal) {
    final Acceptor acceptor = new Acceptor(journal);
    final QuorumConsensusStore store =
        new QuorumConsensusStore(
            self, config::members, acceptor, new TransportPeerCaller(transport), clock,
            callTimeout);

    final Disposable subscription =
        transport
            .listen()
            .subscribe(
                message -> {
                  if (REQUEST_QUALIFIER.equals(message.qualifier())) {
                    answer(transport, acceptor, clock, message);
                  } else if (CONFIG_REQUEST_QUALIFIER.equals(message.qualifier())) {
                    answerConfig(transport, config, message);
                  }
                });

    return new QuorumNode(transport, store, config, subscription);
  }

  private static void answerConfig(Transport transport, QuorumConfig config, Message request) {
    final ConfigRequest req = ConfigCodec.decodeRequest(request.data());
    boolean accepted = false;
    if (!req.isGet() && req.record().epoch() > config.epoch()) {
      accepted = config.adopt(req.record().epoch(), req.record().members());
    }
    final ConfigResponse response =
        ConfigResponse.of(accepted, new ConfigRecord(config.epoch(), config.members()));
    final Message reply =
        Message.withData(ConfigCodec.encode(response))
            .qualifier(CONFIG_RESPONSE_QUALIFIER)
            .correlationId(request.correlationId())
            .build();
    transport.send(request.sender(), reply).subscribe(null, ex -> { });
  }

  /** The live configuration of a dynamic-quorum node, or {@code null} for a static node. */
  public QuorumConfig config() {
    return config;
  }

  private static void answer(
      Transport transport, Acceptor acceptor, LongSupplier clock, Message request) {
    final LeaseRequest leaseRequest = LeaseCodec.decodeRequest(request.data());
    final LeaseResponse response = acceptor.handle(leaseRequest, clock.getAsLong());
    final Message reply =
        Message.withData(LeaseCodec.encode(response))
            .qualifier(RESPONSE_QUALIFIER)
            .correlationId(request.correlationId())
            .build();
    transport.send(request.sender(), reply).subscribe(null, ex -> { });
  }

  /** The store the local elector drives. */
  public ConsensusStore store() {
    return store;
  }

  /** This node's transport address. */
  public String address() {
    return transport.address();
  }

  /** Stops answering and closes the transport. */
  public void stop() {
    subscription.dispose();
    transport.stop().subscribe(null, ex -> { });
  }
}
