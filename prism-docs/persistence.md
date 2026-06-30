# Persistence in prism — durability, the journals, and how they grow

prism keeps its safety-critical state in three small write-ahead files so a node that crashes and
restarts cannot violate its own guarantees — it never elects a second leader, never regresses a
version, and never forgets the quorum configuration it had committed. This page is the operator's
reference for that durability: what exists, when it is on, what each file stores, how it grows, and
how to run it.

> **One-line answer:** durability is **off by default** (state is in-memory); turn it on with
> `PrismConfig.withPersistenceDir(dir)`. It then writes up to three append-/overwrite files under
> that directory — `lease.journal`, `clock.journal`, and (on the dynamic-quorum path)
> `config.journal` — each `fsync`'d before the call that produced it returns.

---

## 1. When durability is on — and when it isn't

By default a prism node holds its acceptor state and clock high-water **in memory only**. That is the
right default for the simulator, for tests, and for an in-process embedding — but it means a crash
loses the durable state the safety argument relies on.

Durability is enabled by giving the node a directory:

```java
PrismConfig config = new PrismConfig(addr, quorum, TcpTransportFactory::new)
    .withPersistenceDir(Path.of("/var/lib/prism"));
Prism prism = new PrismImpl(cluster, config).startAwait();
```

When `persistenceDir()` is non-null, the runtime swaps the no-op journals for file-backed ones
(`PrismImpl`):

- the HLC is constructed with a `FileClockJournal` at `clock.journal`;
- the elector's acceptor is given a `FileLeaseJournal` at `lease.journal`;
- on the dynamic-quorum path only (`withDynamicQuorum`), the quorum config is given a
  `FileConfigJournal` at `config.journal`.

When `persistenceDir()` is null, all three fall back to `…Journal.noop()` and nothing is written.

---

## 2. The three files

| File | Written by | Protects | Path |
|------|-----------|----------|------|
| `lease.journal` | acceptor, every accepted lease/renewal | **never two leaders** across a crash — the durable accepted epoch-floor | `<dir>/lease.journal` |
| `clock.journal` | the Hybrid Logical Clock, once per persist-ahead window | **versions never regress** — the HLC resumes above its high-water | `<dir>/clock.journal` |
| `config.journal` | the quorum (dynamic path only), every committed config | **no reset to the bootstrap seed C0** on restart — the committed member set survives | `<dir>/config.journal` |

`lease.journal` and `config.journal` are **append-only** (one record per line, kept forever — see §4).
`clock.journal` is a **single overwritten value**, not an append log (§3).

### Why each one matters

- **`lease.journal`.** Single-decree quorum consensus is only safe if an acceptor never forgets a
  lease it accepted: a crash that lost that state could let a recovered acceptor accept a *conflicting*
  lease and break "never two leaders". So each acceptance is made write-ahead durable — persisted
  before it is acknowledged — and replayed on restart. (Note the honest caveat in
  [`paxos.md`](paxos.md) §6: the acceptor's *accepted* leases are journaled, but its in-flight
  `promised` ballots are not — the accepted epoch-floor, which is what fencing monotonicity depends
  on, is durable.)
- **`clock.journal`.** The HLC stamps every registry version. If a restarted node re-issued a
  physical value it had already used, last-writer-wins would reject the "new" write as stale. The
  clock persists a value *ahead* of what it has issued and resumes from it, so versions stay
  monotonic across a restart.
- **`config.journal`.** A dynamic-quorum node that restarts must recover the configuration it had
  committed; otherwise it resets to the bootstrap seed C0 at epoch 0, and a whole-cluster restart
  would lose the committed member set entirely.

---

## 3. How it's written

All three journals are **write-ahead** and **`fsync`'d before the producing call returns** — the
record is on stable storage (data *and* metadata) before prism acknowledges the action. The fsync is
`FileChannel.force(true)` in every case.

### `lease.journal` — append-only, one record per line

Each accepted lease (acquisition or renewal) is appended as one tab-separated line, then fsync'd:

```
group<TAB>owner<TAB>epoch<TAB>expiresAt\n
```

`expiresAt` and `epoch` are integers; `group`/`owner` are cluster ids, which never contain tabs or
newlines. On `load()` the file is read line by line and **reduced to the highest-epoch lease per
group** (`highest.merge(group, …, b.epoch() >= a.epoch() ? b : a)`). Older and lower-epoch lines are
ignored at load time but are **not** removed from the file.

### `config.journal` — append-only, one record per line

Each committed configuration is appended as one tab-separated line, then fsync'd:

```
epoch<TAB>member1,member2,member3\n
```

`epoch` is an integer; members are comma-joined `host:port` ids (never containing tabs, commas, or
newlines). On `load()` the file is reduced to the **single highest-epoch** record; lower-epoch lines
are ignored but left in the file.

### `clock.journal` — a single overwritten value

`clock.journal` is **not** an append log. It holds one decimal number — the HLC's persist-ahead
high-water — and each `store()` **truncates and rewrites** the file (`TRUNCATE_EXISTING`), then
fsyncs. The clock persists *ahead* of what it has issued by a fixed window
(`PERSIST_WINDOW_MILLIS = 1000`), so the fsync fires roughly **once per second of clock advance**
rather than once per timestamp. On `load()` the number is parsed (empty file → `0`) and the clock
resumes above it. Because every write overwrites the same value, this file **does not grow**.

---

## 4. What is stored, and for how long

| Journal | What is appended | Retention today |
|---------|------------------|-----------------|
| `lease.journal` | every accepted lease and **every renewal** | append-only, **compacted in place** every ~1024 appends |
| `config.journal` | every committed quorum configuration | append-only, **compacted in place** every ~256 appends |
| `clock.journal` | the latest high-water only | one value, overwritten in place |

`load()` on the two append-only journals reduces the file to the record(s) that matter (highest epoch
per group / overall). Those journals are also **compacted**: after a bounded number of appends the
in-memory reduced state (highest-epoch record per group / the single highest-epoch config) is written
to a temp file, `fsync`'d, and **atomically renamed** over the live file. So the file is rewritten to
its minimal form periodically rather than growing forever — on-disk size and restart cost stay
bounded. (There is still no separate snapshot/checkpoint file; compaction *is* the snapshot, in line.)

---

## 5. How it grows, and how compaction bounds it

`clock.journal` is fixed-size (one value). The two append-only journals grow **between compactions**
and then snap back to their minimal form:

- **`lease.journal` is the dominant driver between compactions.** The elector renews its lease **every
  tick** (deliberately well below the lease TTL), so the holder of each group appends a renewal record
  on every tick. Each line is a few tens of bytes. Left unbounded that would grow forever — so the
  journal **compacts in place every ~1024 appends**, rewriting the file to just the highest-epoch
  record per group. Steady-state size is therefore roughly `(#groups) + (up to ~1024)` lines, not a
  function of uptime.

- **`config.journal` grows only on reconfiguration** (dynamic path) and compacts every ~256 appends
  down to the single highest-epoch config. In a stable cluster it is tiny; even under churn it stays
  bounded.

Compaction is atomic (temp file + `fsync` + atomic rename), so a crash during compaction leaves
either the old or the new file intact — never a half-written one. The compaction interval is an
internal constant today (not yet a tunable); the defaults keep both files small without compacting so
often that the rewrite cost matters.

---

## 6. Risks and limits (be honest)

These are real, current limitations — not theoretical.

| Limit | What it means in practice |
|-------|---------------------------|
| **Disk usage is bounded, not zero** | in-place compaction keeps `lease.journal`/`config.journal` to roughly the compaction interval plus one line per group, so growth no longer scales with uptime. Still budget disk for that working set on fast local storage. |
| **fsync on the hot path** | every accepted lease/renewal does a synchronous `force(true)` before returning. On slow or contended storage that fsync latency lands on the renewal path. (The clock fsyncs only ~once/second, so its cost is negligible.) |
| **Startup replays the current (compacted) file** | `load()` reads the file via `readAllLines` and reduces it in memory. Because the file is compacted, this stays small and restart is fast regardless of how long the node has run. |
| **No per-record checksum** | one file per journal with no per-record checksum. A torn final record (crash mid-append) is now tolerated and dropped on recovery; an *interior* malformed record fails loud rather than silently dropping acknowledged state. There is no rotation or external snapshot file (compaction is in place). |
| **Compaction interval is a constant** | the ~1024 / ~256-append intervals are internal constants, not yet operator-tunable. |
| **Simulation models pause, not state-losing crash** | the deterministic simulator's isolation models a *pause* (the node stops talking and resumes), not a crash that loses in-memory state and reloads from disk. The journal recovery path is covered by the journal unit tests, but it is not exercised by the DST fuzzers. |

None of these affect the *safety* properties — the highest-epoch records are always durable and
correctly recovered — they are durability **operability** limits.

---

## 7. How to operate it

**Enabling it.** Call `withPersistenceDir(dir)` on the `PrismConfig` (see §1). Without it, the node is
in-memory and none of this applies.

**Where to put the directory.** Use a **fast, local** disk. Every accepted lease/renewal blocks on an
fsync to this directory, so network filesystems or slow volumes put their latency directly on the
renewal path. Give the directory to the prism process exclusively; do not share it between nodes.

**Monitor disk usage.** Watch the size of `lease.journal` over time — it grows steadily with the tick
rate (§5) and is the file that will exhaust a disk first. Alert on the directory's free space and on
`lease.journal` size growth, not just on a fixed threshold, since the growth is continuous.

**Backups.** The files are plain text and self-contained; backing up the directory backs up the
durable state. For a consistent snapshot prefer copying while the node is stopped (or use a
filesystem/volume snapshot) — copying a live, actively-appended `lease.journal` can capture a
partially-written final line.

**Current manual mitigation for growth.** There is **no built-in compaction yet**. If a journal has
grown large:

- The only records that matter for correctness are the **highest-epoch** ones (highest epoch per
  group in `lease.journal`; the single highest-epoch config in `config.journal`). Everything below
  them is dead weight that `load()` already ignores.
- **Do not hand-edit the journals while the node is running**, and do not truncate a live file —
  appends and your edit will race, and a half-written file fails to load. Truncation is only safe with
  the node **stopped**.
- Even stopped, hand-editing is risky (one wrong line breaks `load()`); treat it as a last resort and
  keep a backup of the original file first. The supported fix is the planned snapshot-then-truncate
  compaction, not manual surgery.

---

## 8. Where this maps in the code

| Concept | Code |
|---------|------|
| Wiring (in-memory vs. file-backed) | [`PrismImpl`](../prism-runtime/src/main/java/io/scalecube/prism/runtime/PrismImpl.java) (`persistenceDir()` branches) |
| `withPersistenceDir(dir)` | [`PrismConfig`](../prism-runtime/src/main/java/io/scalecube/prism/runtime/PrismConfig.java) |
| Lease journal interface / file impl | [`LeaseJournal`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/LeaseJournal.java) · [`FileLeaseJournal`](../prism-persistence/src/main/java/io/scalecube/prism/persistence/FileLeaseJournal.java) |
| Clock journal interface / file impl | [`ClockJournal`](../prism-versioning/src/main/java/io/scalecube/prism/versioning/ClockJournal.java) · [`FileClockJournal`](../prism-persistence/src/main/java/io/scalecube/prism/persistence/FileClockJournal.java) |
| Config journal interface / file impl | [`ConfigJournal`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/ConfigJournal.java) · [`FileConfigJournal`](../prism-persistence/src/main/java/io/scalecube/prism/persistence/FileConfigJournal.java) |

See also [`paxos.md`](paxos.md) §6 for the `promised`-vs-`accepted` durability caveat, and
[`plan.md`](plan.md) for the compaction backlog item.
