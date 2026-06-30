# prism-persistence

**Layer:** cross-cutting (durability) · **Status:** durable acceptor journal implemented & tested

## Implemented now
- `FileLeaseJournal implements LeaseJournal` (from `prism-consensus`) — a **write-ahead** lease log:
  every accepted lease is appended and `fsync`'d before the acceptor acknowledges, and replayed on
  restart (reduced to the highest epoch per group). This makes the quorum **crash-safe**: a recovered
  acceptor never forgets a promise, so single-decree safety ("never two leaders") holds across
  process crashes — the gap the simulator flagged. Verified by `FileLeaseJournalTest`.

## Implemented now (registry/versioning durability)
- `FileClockJournal implements ClockJournal` (from `prism-versioning`) — a durable, fsync'd
  high-water store for the **Hybrid Logical Clock**. A restarted node resumes above the persisted
  value, so registry versions **never regress** — last-writer-wins keeps accepting the node's updates
  after a crash, even if the wall clock stepped backward. Verified by `FileClockJournalTest`.

## Still planned
Durable persistence of the **registry owned-slice entries** themselves (so a restarted node
re-advertises without the app re-registering); anti-entropy already re-syncs remote slices on rejoin.

## What it does
Durably stores the **local member's own slice** of the registry and its monotonic version, so a
restart resumes cleanly instead of starting from zero.

## Goal
Make versioning correct **across restarts** when a node has a stable identity. Without this, a
restarted node's version resets to 0 while the cluster still holds higher versions from before the
crash — and LWW rejects the node's fresh updates until it climbs back. Persisting the version fixes
that (the memberlist "persist the incarnation" pattern).

## How
- Persist `(owned entries, version)` to a local store (append-only file / embedded KV).
- **Write-ahead discipline:** `fsync` the version bump **before** advertising it. Otherwise a crash
  between advertise and persist causes a torn update / version regression.
- On `start()`, reload and resume advertising at `version + 1`.

## Scope — important
- **Only the local owner's slice is persisted.** Remote slices are *not* — they're soft state owned by
  other nodes, fresher when re-synced via anti-entropy on join. Persisting them would be an
  anti-pattern (stale on restart, must revalidate).
- Only meaningful with a **stable `memberId`** (`ClusterConfig.memberId(...)`). With the default
  ephemeral UUID, a restart is a brand-new member and there's nothing to resume — this module is a
  no-op in that mode.
- If the concern is heap/GC for large remote caches rather than durability, that's a *capacity*
  problem — solve it with off-heap/memory-mapped storage, not here.

## Depends on
`prism-api`, `prism-versioning`, `slf4j-api`
