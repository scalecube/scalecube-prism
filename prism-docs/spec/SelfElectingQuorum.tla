------------------------------ MODULE SelfElectingQuorum ------------------------------
(***************************************************************************
 Formal model of dynamic (self-electing) quorum membership for the lease
 elector - the companion to ADR-0015.

 It extends the static lease model (LeaseElection.tla) with a current and a
 previous committed configuration and a reconfiguration action. It proves TWO
 safety properties across reconfiguration:

   AtMostOneLeader   - never two leaders, because adjacent single-member
     configs have overlapping majorities and an acceptor holds one lease.
   NoTokenRegression - a leader's fencing epoch never goes backwards: no owner
     is ever certified below the highest epoch already committed.

 The key finding this model surfaced: SINGLE-MEMBER reconfiguration alone
 guarantees at-most-one-leader but NOT fencing monotonicity. If reconfiguration
 is unconstrained, a config that SHRINKS can drop below a stale, still-valid,
 lower-epoch lease's majority and resurrect it - a fencing regression that
 AtMostOneLeader does not catch. The fix modeled here (LeaderDriven = TRUE):
 reconfiguration is LEADER-DRIVEN - only the owner currently certified under the
 current config may reconfigure, and it re-establishes its lease (the §7.1
 high-water) on a MAJORITY of the new config, gated by the acceptor rule. So a
 stale valid lease BLOCKS an unsafe shrink until it expires, and the fencing
 token never regresses.

 Two necessity demonstrations (each constraint is load-bearing in isolation):
   - LeaderDriven = FALSE makes reconfiguration an unconstrained config swap;
     TLC then violates NoTokenRegression via a shrink, even at ReconfigDelta = 1
     (SelfElectingQuorum_nofence.cfg).
   - widening ReconfigDelta (>= 2 members per change) violates AtMostOneLeader
     (SelfElectingQuorum_unsafe.cfg).
 ***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS Nodes,            \* all potential acceptors
          Owners,           \* candidate leaders
          NONE,             \* the "no owner" sentinel (a model value, distinct from every Owner)
          MaxEpoch,         \* epoch bound for finite checking
          MinQuorum,        \* minimum configuration size
          ReconfigDelta,    \* max members a single reconfiguration may change (1 = safe)
          LeaderDriven      \* TRUE = leader-driven reconfig that carries the high-water to a majority
                            \* of the new config (the fix); FALSE = unconstrained config swap (naive)

ASSUME NONE \notin Owners

MajorityOf(c) == (Cardinality(c) \div 2) + 1

VARIABLES
  acc,         \* acc[n] = [owner |-> Owners \cup {NONE}, epoch |-> 0..MaxEpoch]
  valid,       \* acceptors whose stored lease is currently unexpired
  config,      \* current committed configuration
  prevConfig,  \* the immediately previous committed configuration (in-flight leases may rely on it)
  maxCommitted \* history var: the highest epoch ever certified by a majority (fencing high-water)

vars == << acc, valid, config, prevConfig, maxCommitted >>

Configs == { c \in SUBSET Nodes : Cardinality(c) >= MinQuorum }

TypeOK ==
  /\ acc \in [Nodes -> [owner : Owners \cup {NONE}, epoch : 0..MaxEpoch]]
  /\ valid \subseteq Nodes
  /\ config \in Configs
  /\ prevConfig \in Configs
  /\ maxCommitted \in 0..MaxEpoch

Init ==
  /\ acc = [n \in Nodes |-> [owner |-> NONE, epoch |-> 0]]
  /\ valid = {}
  /\ \E c \in Configs : Cardinality(c) = MinQuorum /\ config = c /\ prevConfig = c
  /\ maxCommitted = 0

(* The acceptor rule — identical to the static model and to Acceptor.handle. *)
Accept(n, o, e) ==
  \/ acc[n].owner = NONE
  \/ (acc[n].owner = o /\ e >= acc[n].epoch)
  \/ (n \notin valid /\ e > acc[n].epoch)

(* ---- Certification (used by both the reconfiguration guard and the safety properties) ---- *)

Backers(o) == { n \in valid : acc[n].owner = o }

\* Certified under a configuration: a majority of that config currently holds o's valid lease.
CertifiedUnder(o, c) == Cardinality(Backers(o) \cap c) >= MajorityOf(c)

\* The epoch at which owner o is currently certified: the highest epoch among its valid backers.
\* Only evaluated when Backers(o) is non-empty (i.e. when o is certified somewhere).
LeaderEpoch(o) == LET es == { acc[n].epoch : n \in Backers(o) }
                  IN  CHOOSE e \in es : \A f \in es : f <= e

(* A leader acquires/renews by convincing a majority of the CURRENT config. Every successful
   Acquire commits the lease at epoch e, so it advances the fencing high-water. *)
Acquire(o, e) ==
  /\ \E Q \in SUBSET config :
       /\ Cardinality(Q) >= MajorityOf(config)
       /\ \A n \in Q : Accept(n, o, e)
       /\ acc'   = [n \in Nodes |-> IF n \in Q THEN [owner |-> o, epoch |-> e] ELSE acc[n]]
       /\ valid' = valid \cup Q
  /\ maxCommitted' = IF e > maxCommitted THEN e ELSE maxCommitted
  /\ UNCHANGED << config, prevConfig >>

Expire(n) ==
  /\ n \in valid
  /\ valid' = valid \ {n}
  /\ UNCHANGED << acc, config, prevConfig, maxCommitted >>

(* Reconfiguration (ADR-0015 §6/§7), single-step (<= ReconfigDelta members changed).

   LeaderDriven = TRUE (the shipped rule): only the owner o currently certified under the current
   config may reconfigure, and as part of the step it re-establishes o@e (its high-water) on a
   MAJORITY of the new config, gated by the acceptor rule. A stale, still-valid, lower-epoch lease
   therefore BLOCKS an unsafe shrink until it expires, and a majority of every committed config
   carries the high-water — so the fencing token never regresses.

   LeaderDriven = FALSE (naive): an unconstrained config swap. Still single-member at ReconfigDelta=1,
   so AtMostOneLeader holds — but TLC finds a shrink that resurrects a stale lower-epoch lease,
   violating NoTokenRegression. That is why reconfiguration must be leader-driven. *)
Reconfigure ==
  /\ \E c \in Configs :
       /\ c # config
       /\ Cardinality((config \ c) \cup (c \ config)) <= ReconfigDelta
       /\ IF LeaderDriven
          THEN \E o \in Owners :
                 /\ CertifiedUnder(o, config)
                 /\ LET e == LeaderEpoch(o)
                    IN \E Q \in SUBSET c :
                         /\ Cardinality(Q) >= MajorityOf(c)
                         /\ \A n \in Q : Accept(n, o, e)
                         /\ acc'   = [n \in Nodes |-> IF n \in Q THEN [owner |-> o, epoch |-> e]
                                                                 ELSE acc[n]]
                         /\ valid' = valid \cup Q
          ELSE /\ acc'   = acc
               /\ valid' = valid
       /\ prevConfig' = config
       /\ config' = c
  /\ UNCHANGED maxCommitted

Next ==
  \/ \E o \in Owners, e \in 1..MaxEpoch : Acquire(o, e)
  \/ \E n \in Nodes : Expire(n)
  \/ Reconfigure

Spec == Init /\ [][Next]_vars

(* ---- Safety ---- *)

\* A live leader is certified under the current OR the previous config (in-flight leases span a
\* reconfiguration). With single-member reconfig the two configs' majorities overlap, so two distinct
\* owners can never both be certified — that is "never two leaders across reconfiguration".
Leader(o) == CertifiedUnder(o, config) \/ CertifiedUnder(o, prevConfig)

AtMostOneLeader == \A o1, o2 \in Owners : (o1 # o2) => ~(Leader(o1) /\ Leader(o2))

\* FENCING MONOTONICITY: any current leader's epoch is at least the highest epoch ever committed —
\* leadership never regresses to a stale fencing token. Leader-driven reconfiguration (carrying the
\* high-water onto a majority of every new config) is what makes this hold across a shrink; an
\* unconstrained config swap (LeaderDriven = FALSE) violates it even at single-member granularity.
NoTokenRegression == \A o \in Owners : Leader(o) => LeaderEpoch(o) >= maxCommitted
=============================================================================
