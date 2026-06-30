# The schema'd binary codec — why prism has no Java serialization

prism sends bytes between nodes for three message families — registry gossip deltas, consensus
lease messages, and reconfiguration messages — and **every one of them is encoded by a hand-written,
schema'd binary codec**. There is no `ObjectInputStream` anywhere in the wire path. This page
explains the threat that motivates that choice, how the codec is actually built, why it closes the
deserialization-RCE class *by construction*, and exactly what it does and does not buy you.

> **One-line answer:** every prism message is read field-by-field into a *known* DTO by a
> `DataInput`-based parser that instantiates nothing the bytes choose — so the Java
> native-deserialization gadget class (untrusted bytes → `readObject` → arbitrary code) simply does
> not exist for prism traffic. It is **not** encryption and **not** authentication — that is the
> transport's job.

---

## 1. The threat: `ObjectInputStream` is a code-execution primitive

Java's native deserialization (`ObjectInputStream.readObject`) is not a parser — it is a
**data-directed object factory**. The byte stream *names the classes* to instantiate, and the runtime
invokes their `readObject`/`readExternal` (and historically finalizers) while reconstructing the
graph. An attacker who can submit bytes can therefore stitch together a **gadget chain** — a
sequence of side effects across whatever classes happen to be on the classpath — and reach remote
code execution, *even if your own DTO is completely benign*. The defect is the **mechanism**
(data choosing code), not any one type, which is why marking DTOs `Externalizable` does not help:
the stream still drives instantiation.

This is the most thoroughly documented RCE class on the JVM — the basis of a long line of CVEs,
analyzed by Frohoff & Lawrence (*Marshalling Pickles*, 2015) and codified as CERT rule **SER12-J,
"Prevent deserialization of untrusted data."** For a distributed service that receives bytes from
every peer in the cluster, "untrusted data" is the *normal* case. (See
[`decisions/0009-schema-codec-no-jdk-serialization.md`](decisions/0009-schema-codec-no-jdk-serialization.md).)

---

## 2. The approach: schema-directed parsing, nothing reflective

The robust fix is to invert the relationship: **read a fixed set of fields into known types, and let
the bytes choose nothing.** prism's codec layer is deliberately tiny —
[`prism-codec`](../prism-codec/src/main/java/io/scalecube/prism/codec/) is two classes:

- [`WireWriter`](../prism-codec/src/main/java/io/scalecube/prism/codec/WireWriter.java) wraps a
  `DataOutputStream` and exposes **only** `writeByte` / `writeBoolean` / `writeInt` / `writeLong` /
  `writeString` (a presence flag plus length-prefixed UTF). There is no `writeObject`.
- [`WireReader`](../prism-codec/src/main/java/io/scalecube/prism/codec/WireReader.java) wraps a
  `DataInputStream` and exposes the mirror primitives. It **never calls `readObject`**, so a payload
  can never coerce it into class instantiation.

On top of these primitives, each module writes a per-message codec that knows the message's exact
schema. The shape is always the same:

1. write a leading **version byte** (`VERSION = 1`), then each field in a **fixed order** with an
   **explicit type**;
2. on decode, **check the version byte first** (a mismatch throws `IllegalArgumentException`), then
   read the same fields back in the same order into the module's own concrete DTO.

Variable-length structures are **length-prefixed** and the count drives a bounded loop — e.g.
`ConfigCodec` writes `members.size()` then exactly that many strings; `RegistryGossipCodec` does the
same for its property map. Nullable references are guarded by an explicit presence boolean
(`writeBoolean(lease != null)`), never by sentinel objects. The reader's structure is a literal
mirror of the writer's, so the only thing it can ever produce is the declared DTO.

---

## 3. Why this eliminates the gadget class entirely

The security property is not "we filtered the dangerous classes" — it is **there is no mechanism by
which the bytes can name a class at all.** A `WireReader` can do exactly five things: return a
`byte`, a `boolean`, an `int`, a `long`, or a `String`. The decode method then hands those scalars
to a normal Java constructor (`new LeaseRecord(group, owner, epoch, expiresAt)`). No reflection, no
polymorphism, no object graph, no callback during construction. A malicious payload's *worst* outcome
is a wrong-but-typed value, a bounds/format exception, or a rejected version byte — never a side
effect during parsing.

Crucially this holds **independent of the transport's own codec.** prism puts a `byte[]` on the wire
(`Message.withData(LeaseCodec.encode(...))` in
[`QuorumNode.java`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/QuorumNode.java)),
so even if the underlying messaging layer still used JDK serialization, the only thing it would
deserialize is a `byte[]` — itself gadget-safe — and prism's schema decode takes over from there.
The DTOs are plain classes; `Externalizable` was removed (the DTO Javadoc states the message "crosses
the wire via `LeaseCodec`, never Java serialization").

Secondary benefits fall out of the same design, not by accident:

- **Compactness & deterministic size.** Fixed primitives + length-prefixed strings means the encoded
  size is a known function of the field values — no class descriptors, no per-message reflection
  metadata that JDK serialization prepends.
- **Wire stability.** The version byte plus *additive-only, position-stable* schemas make the format
  an explicit compatibility contract rather than an accident of class shape.
- **Cross-language friendliness.** A `DataInput`-style record of primitives is trivially readable
  from any language; a JDK object stream is effectively JVM-only.

---

## 4. How it maps to prism's code

Each message family has its own codec; all of them stand on the same two `prism-codec` primitives.

| Message (DTO) | Codec | Schema highlights |
|---|---|---|
| Lease `LeaseRequest` / `LeaseResponse` (GET, PREPARE, ACCEPT, PROMISE) | [`LeaseCodec.java`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/LeaseCodec.java) | version byte; `isGet` flag selects GET/PREPARE-vs-ACCEPT; lease as `owner`+`epoch`+`expiresAt`; presence flag for the current lease. |
| Reconfiguration `ConfigRequest` / `ConfigResponse` | [`ConfigCodec.java`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/ConfigCodec.java) | version byte; `isGet` flag; `ConfigRecord` = `epoch` + length-prefixed member list. |
| Registry gossip delta `RegistryGossip` | [`RegistryGossipCodec.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/RegistryGossipCodec.java) | version byte; flat fields (`service`, `owner`, `address`, `tier`, two clocks, `tombstone`) + length-prefixed property map. |
| Wire primitives (shared substrate) | [`WireWriter.java`](../prism-codec/src/main/java/io/scalecube/prism/codec/WireWriter.java) / [`WireReader.java`](../prism-codec/src/main/java/io/scalecube/prism/codec/WireReader.java) | `byte`/`boolean`/`int`/`long`/nullable-UTF only; no `readObject`/`writeObject`. |

The call sites confirm the boundary: the consensus transport encodes/decodes via the codecs at the
edge ([`TransportPeerCaller.java`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/TransportPeerCaller.java),
[`TransportConfigReplicator.java`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/TransportConfigReplicator.java)),
and the registry decodes every inbound gossip with `RegistryGossipCodec.decode(gossip.data())`
([`GossipServiceRegistry.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/GossipServiceRegistry.java)).
The codec types never leak into `prism-api`, which stays serialization-agnostic.

---

## 5. What is explicitly *out* of scope

A schema codec removes a specific, severe vulnerability class. It is **not** a security blanket, and
pretending otherwise would be the dangerous mistake.

| Not provided here | Where it belongs |
|---|---|
| **Confidentiality / encryption** | the transport (TLS, or the messaging layer). The codec writes plaintext fields; anyone on the wire can read them. |
| **Authentication / integrity** | the transport, plus message authentication for gossip. A schema codec happily decodes a *spoofed-but-well-formed* message — it only guarantees the bytes can't execute. |
| **Authorization** | the protocol logic (the acceptor's lease rules, quorum checks), not the parser. |
| **A general-purpose serialization framework** | prism is not shipping a Protobuf replacement; these are a handful of fixed, internal schemas. |

This split is deliberate. ADR-0009 frames the codec as the **prerequisite** for a future UDP move
(ADR-0008), where source spoofing makes an authenticated, non-executable wire format mandatory — the
non-executable half is done here; gossip-message authentication is the companion step still on the
roadmap ([`plan.md`](plan.md)). The honest claim is precise: prism **closes the
deserialization-RCE class and gives a stable schema**, and leaves secrecy and identity to the layers
that own them.

---

## 6. Evidence

The "no Java serialization" claim is checkable, not aspirational: a repository-wide search for
`Serializable`, `Externalizable`, `ObjectInputStream`, `ObjectOutputStream`, `readObject`, and
`writeObject` across all `*.java` finds **zero usages** — the only hit is the words *"never calls
`readObject`"* inside the `WireReader` Javadoc. Each codec is exercised by a focused round-trip test
([`WireCodecTest`](../prism-codec/src/test/java/io/scalecube/prism/codec/WireCodecTest.java),
`LeaseCodecTest`, `ConfigCodecTest`, `RegistryGossipCodecTest`), and `LeaseCodecTest` additionally
asserts that a **corrupted version byte is rejected** with `IllegalArgumentException` rather than
silently mis-parsed. The building-blocks table in the [`README`](../README.md) records the same
property — *"nothing on the wire can trigger a deserialization-gadget RCE"* — and the broader safety
story is in [`guarantees.md`](guarantees.md).
