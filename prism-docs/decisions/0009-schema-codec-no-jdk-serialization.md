# 0009 — Schema'd wire codec; no JDK serialization

Status: **Accepted** (rationale expanded to research grade; decision unchanged)

## Context
scalecube's DTOs use Java `Externalizable`, and the default message codec routes through Java's
`ObjectInputStream`. prism adds new on-the-wire message families (registry deltas, consensus
entries/replies, elector messages). The choice of how to encode them is a **security** decision before
it is a performance one.

## Theory: why Java deserialization is a security defect
`ObjectInputStream.readObject` is a **Turing-complete deserializer**: the byte stream names the classes
to instantiate, and the runtime invokes their `readObject`/`readExternal`/finalizers during
reconstruction. An attacker who can submit bytes can therefore drive a **gadget chain** — a sequence
of `readObject` side effects across whatever classes are on the classpath — to achieve arbitrary code
execution, even if *your* DTO is benign. This is a well-documented vulnerability class (the basis of
numerous CVEs; analyzed by Frohoff & Lawrence, and codified in CERT Oracle rule SER12-J: "Prevent
deserialization of untrusted data"). The flaw is the **mechanism** (data-directed class
instantiation), not any particular type — so making DTOs `Externalizable` does not remove it.

The robust fix is **schema-directed parsing**: read a *fixed* set of fields into *known* types,
instantiating nothing the bytes choose. This is the data-vs-code separation that text/binary schema
codecs (Protobuf, msgpack, Avro) provide, and that a hand-written `DataInput` reader provides equally.

## Decision
All prism wire messages use a **schema'd binary codec** with no Java serialization:
- `prism-codec` provides `WireWriter`/`WireReader` over `DataOutput/InputStream` — primitives and
  length-prefixed UTF only, never `readObject`; a leading **version byte** allows wire evolution.
- Each module encodes its own DTOs to `byte[]` with an explicit, versioned schema (`LeaseCodec`,
  `RegistryGossipCodec`). DTOs are plain classes (`Externalizable` removed).
- On the wire, messages carry a `byte[]` payload, so even under the default JDK message codec only a
  `byte[]` (gadget-safe) is deserialized; prism's schema decode then instantiates only its own known
  types from fixed fields.

## Consequences
- The deserialization-RCE surface is closed for prism's own traffic, independent of the transport's
  codec choice. (Closing scalecube's *own* DTO path is a separate, upstream concern.)
- Schemas are a compatibility contract: **additive only**, never repurpose field positions; the
  version byte gates incompatible changes.
- Codec types must not leak into `prism-api` (it stays serialization-agnostic).
- This is a prerequisite for any UDP move (ADR-0008), where source spoofing makes authenticated,
  non-executable wire formats mandatory; gossip-message authentication is the companion step.

## References
1. Frohoff & Lawrence. *Marshalling Pickles: deserialization vulnerabilities.* AppSecCali, 2015.
2. CERT Oracle Secure Coding — *SER12-J: Prevent deserialization of untrusted data.*
3. OWASP. *Deserialization of Untrusted Data.*
4. Saltzer & Schroeder. *The Protection of Information in Computer Systems*, 1975 (least privilege /
   economy of mechanism — the principles a schema codec honors).
