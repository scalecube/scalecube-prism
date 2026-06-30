package io.scalecube.prism.consensus;

import java.time.Duration;
import java.util.Objects;

/**
 * {@link LeaseTransfer} over a {@link PeerCaller}: pushes the fencing high-water to a joining
 * member by sending {@code LeaseRequest.accept(highWater)} (ADR-0015 §7.1). The joiner's acceptor —
 * already on the lease request qualifier — adopts it, raising its epoch floor. Best-effort and
 * bounded: a failed transfer just means the manager retries on its next tick.
 */
public final class TransportLeaseTransfer implements LeaseTransfer {

  private final PeerCaller caller;
  private final Duration timeout;

  /**
   * Creates a transport-backed lease transfer.
   *
   * @param caller the peer caller used to reach acceptors
   * @param timeout per-transfer timeout
   */
  public TransportLeaseTransfer(PeerCaller caller, Duration timeout) {
    this.caller = Objects.requireNonNull(caller, "caller");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public boolean transfer(String member, LeaseRecord highWater) {
    final LeaseResponse response =
        caller
            .call(member, LeaseRequest.accept(highWater))
            .onErrorReturn(LeaseResponse.fail())
            .block(timeout);
    return response != null && response.ok();
  }
}
