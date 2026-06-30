# 0006 — Safe singleton election needs consensus + fencing, not gossip

Status: **Accepted** (rationale expanded to research grade; decision unchanged)

## Context
The headline requirement: for each group, **at most one Active member, ever**, switching only when the
holder is genuinely gone. The tempting cheap implementation — "the lowest-id alive member in the
gossip view is the leader" — is unsafe, and it is worth stating *why* precisely.

## Theory: gossip cannot elect safely
SWIM is an **unreliable failure detector**. In an asynchronous network you cannot distinguish a
crashed node from a slow or partitioned one — formally, perfect failure detection (`P`) is
unimplementable in asynchrony, and **FLP (1985)** shows consensus itself is impossible without
additional assumptions. A gossip rule that maps the *local* membership view to a leader therefore
inherits the view's disagreements: under a partition or a false-positive suspicion, two sides hold
different "lowest-id alive" members and **both elect** → split-brain.

Election *is* consensus (agreement on a single value, the leader). By Chandra–Toueg–Hadzilacos (1996)
the weakest detector that solves consensus is `◇W`, and it requires a **majority of correct
processes** to be safe — i.e., a quorum. Gossip provides dissemination and an `◇S`-ish *detector*, but
**not agreement**; it cannot, on its own, guarantee a single decision. Hence consensus is mandatory.

## "Really dead" is undecidable — so make being wrong harmless
Because dead-vs-slow is undecidable, we cannot wait for certainty. The correct reformulation:

> Switch when the **lease expires** *and* the **quorum agrees**, and attach a **fencing token**
> (monotonic epoch) to every leader action so that if we were wrong (the old leader was only
> partitioned), its continued actions are **rejected downstream**.

Fencing (Kleppmann) converts an *undecidable liveness question* into a *safe-by-construction* one: a
mistaken or early failover cannot cause two effective leaders, because the stale leader's lower-epoch
actions are fenced. This is the lease + epoch design realized in ADR-0012.

## Decision
Implement election on the **`CONSENSUS` tier**, never gossip:
1. consensus grants a **lease + monotonic epoch** → at most one winner per epoch (mutual exclusion);
2. the Active member **renews** → stickiness (switch only on real loss);
3. **fencing tokens** make an early/mistaken failover harmless;
4. SWIM/Lifeguard provides *fast, accurate detection* to drive handoff, but never the *decision*.

## Consequences
- The elector depends on `prism-consensus` (a quorum), not gossip.
- A brief **zero-Active failover gap** on ungraceful death is unavoidable (CP; safety over
  availability); graceful `resign`/`LEAVING` makes handoff near-instant.
- **Guardrail:** shipping a gossip-only elector is forbidden — it is provably unsafe under partition.

## References
1. Fischer, Lynch, Paterson. *Impossibility of Distributed Consensus with One Faulty Process.* 1985.
2. Chandra, Hadzilacos, Toueg. *The Weakest Failure Detector for Solving Consensus.* JACM, 1996.
3. Kleppmann. *How to do distributed locking* (fencing tokens). 2016.
4. Burrows. *The Chubby Lock Service.* OSDI, 2006 (leases + sequencers in practice).
