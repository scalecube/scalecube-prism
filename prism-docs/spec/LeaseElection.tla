-------------------------------- MODULE LeaseElection --------------------------------
(***************************************************************************)
(* Formal specification of prism's single-decree Paxos lease elector       *)
(* (the safety kernel behind `prism-consensus`).                            *)
(*                                                                          *)
(* This models the SHIPPED two-phase protocol:                              *)
(*                                                                          *)
(*   Phase 1 (PREPARE / promise).  A proposer picks a ballot b and asks a   *)
(*   quorum to PROMISE it. An acceptor promises iff b >= its highest        *)
(*   promised ballot, recording b as its new promise. (`Acceptor.promised`) *)
(*                                                                          *)
(*   Phase 2 (ACCEPT).  With a majority promise, the proposer asks the      *)
(*   quorum to ACCEPT lease [owner, epoch=b]. An acceptor accepts iff:      *)
(*     (a) the LEASE RULE holds  -- free, or same-owner renew at >= epoch,  *)
(*         or expired takeover at a STRICTLY higher epoch; AND              *)
(*     (b) the PROMISE GUARD holds -- b is not below its promised ballot,   *)
(*         UNLESS this is a still-valid same-owner renewal (which is        *)
(*         exempt, matching `validSameOwnerRenewal` in Acceptor.java).      *)
(*   On accept it also promotes its promise to b (a committed ballot is     *)
(*   promised).                                                             *)
(*                                                                          *)
(* A candidate is leader for an instant iff a MAJORITY of acceptors hold a  *)
(* VALID (unexpired) lease for it. The ballot doubles as the fencing epoch. *)
(*                                                                          *)
(* Checked invariants: TypeOK, AtMostOneLeader (mutual exclusion) and       *)
(* AgreementPerEpoch (single-decree). Run with the constants in             *)
(* LeaseElection.cfg.                                                       *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS Nodes,      \* set of acceptor identities
          Owners,     \* set of candidate identities
          NONE,       \* the "no owner" marker (a model value, distinct from Owners)
          MaxEpoch    \* ballot/epoch bound (for finite model checking)

Majority == (Cardinality(Nodes) \div 2) + 1

VARIABLES
  acc,       \* acc[n]      = accepted lease [owner |-> Owners \cup {NONE}, epoch |-> 0..MaxEpoch]
  valid,     \* set of acceptors whose stored lease is currently unexpired
  promised   \* promised[n] = highest ballot acceptor n has promised (Paxos phase 1)

vars == << acc, valid, promised >>

TypeOK ==
  /\ acc \in [Nodes -> [owner : Owners \cup {NONE}, epoch : 0..MaxEpoch]]
  /\ valid \subseteq Nodes
  /\ promised \in [Nodes -> 0..MaxEpoch]

Init ==
  /\ acc = [n \in Nodes |-> [owner |-> NONE, epoch |-> 0]]
  /\ valid = {}
  /\ promised = [n \in Nodes |-> 0]

(***************************************************************************)
(* Phase 1: PREPARE. A proposer reserves ballot b at some subset of       *)
(* acceptors; each promises iff b exceeds what it has already promised.    *)
(* Leaving the chosen subset arbitrary is the most adversarial model --    *)
(* safety must hold for every interleaving of partial promises.            *)
(***************************************************************************)
Prepare(b) ==
  /\ \E Q \in SUBSET Nodes :
       promised' = [n \in Nodes |-> IF n \in Q /\ b > promised[n] THEN b ELSE promised[n]]
  /\ UNCHANGED << acc, valid >>

(* The LEASE RULE: would acceptor n accept lease (o, e) on its own merits? *)
RuleOk(n, o, e) ==
  \/ acc[n].owner = NONE                           \* free acceptor accepts anything
  \/ (acc[n].owner = o /\ e >= acc[n].epoch)       \* same owner renews at >= epoch
  \/ (n \notin valid /\ e > acc[n].epoch)          \* expired takeover, strictly higher

(* The PROMISE GUARD: a ballot below the promised floor is rejected, EXCEPT *)
(* a still-valid same-owner renewal, which is exempt (validSameOwnerRenewal).*)
PromiseOk(n, o, e) ==
  \/ e >= promised[n]
  \/ (acc[n].owner = o /\ n \in valid)

(* Phase 2 decision at acceptor n for proposal (o, e=ballot). *)
Accept(n, o, e) == RuleOk(n, o, e) /\ PromiseOk(n, o, e)

(***************************************************************************)
(* Phase 2: ACCEPT. Owner o commits lease at epoch e iff a majority        *)
(* accept; those acceptors store the lease, mark it valid, and promote     *)
(* their promise to e.                                                      *)
(***************************************************************************)
Acquire(o, e) ==
  /\ \E Q \in SUBSET Nodes :
       /\ Cardinality(Q) >= Majority
       /\ \A n \in Q : Accept(n, o, e)
       /\ acc'      = [n \in Nodes |-> IF n \in Q THEN [owner |-> o, epoch |-> e] ELSE acc[n]]
       /\ valid'    = valid \cup Q
       /\ promised' = [n \in Nodes |-> IF n \in Q /\ e > promised[n] THEN e ELSE promised[n]]

(* A valid lease may expire at an acceptor; the epoch/promise are retained. *)
Expire(n) ==
  /\ n \in valid
  /\ valid' = valid \ {n}
  /\ UNCHANGED << acc, promised >>

Next ==
  \/ \E b \in 1..MaxEpoch : Prepare(b)
  \/ \E o \in Owners, e \in 1..MaxEpoch : Acquire(o, e)
  \/ \E n \in Nodes : Expire(n)

Spec == Init /\ [][Next]_vars

(* ---- Properties ---- *)

\* A node is "backing" owner o iff it holds a VALID lease for o.
Backers(o) == { n \in valid : acc[n].owner = o }

Leader(o) == Cardinality(Backers(o)) >= Majority

\* SAFETY: never two distinct owners are majority-backed at the same time.
AtMostOneLeader == \A o1, o2 \in Owners : (o1 # o2) => ~(Leader(o1) /\ Leader(o2))

\* SINGLE-DECREE: at most one owner can be majority-backed at any single epoch.
BackersAt(o, e) == { n \in valid : acc[n].owner = o /\ acc[n].epoch = e }
AgreementPerEpoch ==
  \A o1, o2 \in Owners, e \in 1..MaxEpoch :
    (Cardinality(BackersAt(o1, e)) >= Majority /\ Cardinality(BackersAt(o2, e)) >= Majority)
      => (o1 = o2)
=============================================================================
