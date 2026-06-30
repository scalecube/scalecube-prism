# 0008 — TCP stays; no UDP/Aeron in the core until measured

Status: **Accepted** (rationale expanded to research grade; decision unchanged)

## Context
SWIM was designed for an unreliable datagram service, and the obvious "optimization" is to move the
membership hot path to UDP or a high-performance transport (Aeron). We resist this until measurement
justifies it.

## Theory: traffic shape decides the transport
A transport is well- or ill-matched by the *shape* of its traffic:

- **SWIM probe/gossip traffic** is **high-cardinality** (each node touches many distinct peers via
  round-robin probing and fan-out), **low-volume-per-peer**, and **loss-tolerant** — indeed SWIM
  *interprets loss as signal* (a dropped probe → ping-req → suspicion). The original SWIM paper assumes
  UDP precisely because reliability/ordering are unnecessary and counter-productive here: TCP's
  retransmission and head-of-line blocking make a *lost* probe look *slow*, blurring the very signal
  the detector needs.
- **Aeron** is engineered for the opposite shape: **low-cardinality, high-throughput, reliable**
  streams (single-digit-µs latency, ordered, flow-controlled). Per-peer publications carry multi-MB
  log buffers; thousands of peers ⇒ huge off-heap footprint. And its **media driver** is a separate
  process/IPC surface that breaks the embeddable-library property (ADR-0001).

So Aeron is a superb transport for the *wrong* traffic shape here, and its operational weight is
disqualifying for an embedded membership library. UDP *is* idiomatic for SWIM, but TCP's reliability
actually **aids leader stickiness** (fewer spurious losses → fewer false suspicions → fewer failovers,
ADR-0006), and at our target scale the connection cost is not the bottleneck.

## Amdahl/operational argument
At SWIM's cadence (probe ~1 s, gossip ~200 ms), transport latency is five orders of magnitude below
the protocol period — optimizing it cannot move end-to-end behavior (an Amdahl ceiling). The dominant
costs are convergence time and failure-detection windows, governed by protocol parameters, not by µs
of transport latency. Premature transport optimization buys nothing measurable while adding risk.

## Decision
Keep scalecube's **TCP/Netty** transport. Do **not** adopt UDP, Aeron, or a self-electing quorum
**preemptively** — each is a *measured* optimization, taken only when a real limit is observed
(thousands of nodes, tight detection SLAs, connection-table pressure). If a durable, totally-ordered
log for config/metadata or a CP control tier is ever needed, **Aeron Archive / Aeron Cluster** is a
candidate for *that tier*, beside the gossip core — never replacing it.

## Consequences
- Lower complexity now; **Lifeguard** (not the transport) is the lever for failure-detection
  stability.
- If pursued, UDP is a **hybrid** (best-effort probes/gossip; reliable sync/large payloads, à la
  memberlist) and requires the schema codec (ADR-0009) and gossip authentication first.

## References
1. Das, Gupta, Motwani. *SWIM.* DSN, 2002 (UDP, loss-as-signal).
2. Dadgar et al. *Lifeguard.* 2017 (stability without transport changes).
3. Thompson, Montgomery et al. *Aeron* (design: reliable multicast/IPC for high-throughput streams).
4. Amdahl. *Validity of the Single Processor Approach…* AFIPS, 1967 (the optimization ceiling).
