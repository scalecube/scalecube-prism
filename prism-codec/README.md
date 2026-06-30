# prism-codec

**Layer:** cross-cutting (security/wire) · **Status:** implemented — `WireWriter`/`WireReader` + per-module schemas

## What it does
Owns the on-the-wire encoding for everything prism sends: registry deltas, consensus log entries,
and elector messages.

## Goal
Provide a compact, **schema'd**, forward/backward-compatible binary format — and explicitly **avoid
Java native serialization** on the network.

## How
- A schema-first codec (msgpack or protobuf) per message family.
- Versioned message envelopes so rolling upgrades don't break the wire.
- Small, allocation-aware encode/decode paths (the consensus tier is latency-sensitive).

## Why this matters (security)
scalecube-cluster's existing DTOs use Java `Externalizable`; if the JDK codec is ever on the path,
untrusted input flows into Java deserialization — a remote-code-execution class of bug. prism keeps
its *own* messages off that path entirely by using a schema'd codec here. This is a prerequisite
before any move to UDP (where source spoofing is trivial), and pairs with gossip-message
authentication.

## Important
- Treat the schemas as a compatibility contract: additive changes only, never repurpose field ids.
- Don't leak codec types into `prism-api` — the api stays serialization-agnostic.

## Depends on
`prism-api`
