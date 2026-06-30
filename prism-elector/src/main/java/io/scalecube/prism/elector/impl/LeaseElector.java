package io.scalecube.prism.elector.impl;

import io.scalecube.cluster.Member;
import io.scalecube.prism.consensus.ConsensusStore;
import io.scalecube.prism.consensus.LeaseRecord;
import io.scalecube.prism.elector.Leadership;
import io.scalecube.prism.elector.Preference;
import io.scalecube.prism.elector.SingletonElector;
import io.scalecube.prism.metrics.Metrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Lease-based {@link SingletonElector}. Safety ("never two Actives") comes entirely from the
 * linearizable {@link ConsensusStore}: at most one member can win the compare-and-set for a group's
 * lease per epoch. The {@code epoch} is a monotonic fencing token; renewal keeps leadership sticky;
 * lease expiry promotes a standby.
 *
 * <p>The decision logic is deterministic; {@link #tick()} can be driven manually in tests or
 * periodically via {@link #start(Duration)}. Each consensus round is structured as plan (under the
 * lock) → store I/O (lock released) → commit (under the lock), so the <b>blocking</b> quorum round
 * and the acceptor's {@code fsync} never run while the lock is held — a slow renewal cannot stall
 * {@code campaign}/{@code leadership}/other groups. A per-group {@link #roundInFlight} guard keeps
 * same-group rounds serialized despite the released lock. Safety ("never two") is unaffected: it
 * rests entirely on the {@link ConsensusStore} (quorum intersection + fencing), never on holding
 * this lock across the round — distinct electors each have their own lock regardless.
 *
 * <p><b>Distribution caveat:</b> with {@code InMemoryConsensusStore} this is single-JVM only. A
 * cross-node, partition-safe elector requires the distributed {@code QuorumConsensusStore}
 * (single-decree Paxos over a majority quorum; ADR-0012).
 */
public final class LeaseElector implements SingletonElector {

  private static final Sinks.EmitFailureHandler EMIT =
      Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1));

  private final Member localMember;
  private final ConsensusStore store;
  private final Function<String, Optional<Member>> memberResolver;
  private final long leaseTtlMillis;
  private final LongSupplier clock;
  private final Metrics metrics;
  private final LongSupplier acquireBackoffMillis; // randomized cooldown after a contended round
  private final long ballotTag; // per-node low bits making every ballot globally unique

  private final Object lock = new Object();
  private final Set<String> campaigning = new HashSet<>();
  private final Map<String, Long> ledEpoch = new HashMap<>();
  private final Map<String, Sinks.Many<Leadership>> sinks = new HashMap<>();
  // Groups with a consensus round (PREPARE/ACCEPT/renew) currently executing OFF the lock. A second
  // round for the same group (e.g. the ticker while a user campaign() is mid-flight) is skipped, so
  // the blocking store I/O is never held under the lock yet same-group rounds stay serialized.
  private final Set<String> roundInFlight = new HashSet<>();

  // Leader affinity (ADR-0016)
  private final Map<String, Supplier<Preference>> preferences = new HashMap<>();
  private final Map<String, Long> yieldWindowMillis = new HashMap<>();
  private final Set<String> autoMoveGroups = new HashSet<>();
  private final Set<String> holdOnly = new HashSet<>(); // Mode B: promoted (renew, no re-acquire)
  private final Map<String, Long> acquirableSince = new HashMap<>(); // for the yield window
  private final Map<String, Preference> lastPreference = new HashMap<>(); // for the auto-move edge
  private final Map<String, Long> backoffUntil = new HashMap<>(); // anti-dueling acquire cooldown
  private final Map<String, Long> ballotCounter = new HashMap<>(); // monotone Paxos ballot counter

  private ScheduledExecutorService ticker;

  /**
   * Creates an elector.
   *
   * @param localMember the local member (carried on leadership events)
   * @param store the linearizable lease store providing the safety guarantee
   * @param memberResolver resolves an owner id to a {@link Member} (for {@link
   *     #currentLeader(String)})
   * @param leaseTtl how long an acquired lease is valid before it must be renewed
   * @param clock physical time source in millis (injectable for tests/simulator)
   */
  public LeaseElector(
      Member localMember,
      ConsensusStore store,
      Function<String, Optional<Member>> memberResolver,
      Duration leaseTtl,
      LongSupplier clock) {
    this(localMember, store, memberResolver, leaseTtl, clock, Metrics.NOOP);
  }

  /**
   * Creates an elector with a metrics sink.
   *
   * @param localMember the local member (carried on leadership events)
   * @param store the linearizable lease store providing the safety guarantee
   * @param memberResolver resolves an owner id to a {@link Member} (for {@link
   *     #currentLeader(String)})
   * @param leaseTtl how long an acquired lease is valid before it must be renewed
   * @param clock physical time source in millis (injectable for tests/simulator)
   * @param metrics metrics sink
   */
  public LeaseElector(
      Member localMember,
      ConsensusStore store,
      Function<String, Optional<Member>> memberResolver,
      Duration leaseTtl,
      LongSupplier clock,
      Metrics metrics) {
    this(localMember, store, memberResolver, leaseTtl, clock, metrics, () -> 0L);
  }

  /**
   * Creates an elector with a metrics sink and a randomized acquire backoff. The backoff breaks
   * dueling proposers: after a contended Paxos round fails, this node waits a random {@code
   * acquireBackoffMillis} before retrying, so one proposer pulls ahead. Pass {@code () -> 0L} for
   * deterministic (no-backoff) single-threaded tests/simulation.
   *
   * @param localMember the local member (carried on leadership events)
   * @param store the linearizable lease store providing the safety guarantee
   * @param memberResolver resolves an owner id to a {@link Member}
   * @param leaseTtl how long an acquired lease is valid before it must be renewed
   * @param clock physical time source in millis (injectable for tests/simulator)
   * @param metrics metrics sink
   * @param acquireBackoffMillis supplies a randomized cooldown (ms) after a contended round
   */
  public LeaseElector(
      Member localMember,
      ConsensusStore store,
      Function<String, Optional<Member>> memberResolver,
      Duration leaseTtl,
      LongSupplier clock,
      Metrics metrics,
      LongSupplier acquireBackoffMillis) {
    this.localMember = Objects.requireNonNull(localMember, "localMember");
    this.store = Objects.requireNonNull(store, "store");
    this.memberResolver = Objects.requireNonNull(memberResolver, "memberResolver");
    this.leaseTtlMillis = Objects.requireNonNull(leaseTtl, "leaseTtl").toMillis();
    this.clock = Objects.requireNonNull(clock, "clock");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.acquireBackoffMillis = Objects.requireNonNull(acquireBackoffMillis, "backoff");
    // Per-node low bits that disambiguate same-counter ballots. SplitMix64-mix the id before mask
    // so well- and poorly-distributed ids alike spread across the 20-bit tag space (raw String
    // hashCodes cluster). NOTE: this is best-effort UNIQUENESS for LIVENESS only — distinct ballots
    // help dueling proposers converge. Safety never depends on it: it rests on quorum intersection
    // and the promise guard, not on ballot uniqueness; the randomized acquire backoff is the real
    // anti-dueling mechanism. A 20-bit tag collides by the birthday bound only at ~1k members, far
    // beyond the small elector quorum.
    this.ballotTag = mix(localMember.id().hashCode()) & 0xFFFFF; // 20 low bits, always >= 0
  }

  /** SplitMix64 finalizer — spreads ids across the tag space so similar ids don't collide. */
  private static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  @Override
  public Mono<Void> campaign(String group) {
    return Mono.fromRunnable(
        () -> {
          synchronized (lock) {
            campaigning.add(group);
          }
          tryAcquire(group); // store I/O happens off the lock
        });
  }

  @Override
  public Mono<Void> resign(String group) {
    return Mono.fromRunnable(
        () -> {
          synchronized (lock) {
            campaigning.remove(group);
          }
          releaseIfHeld(group); // store I/O happens off the lock
        });
  }

  @Override
  public void affinity(
      String group, Supplier<Preference> preference, Duration yieldWindow, boolean autoMove) {
    synchronized (lock) {
      preferences.put(group, Objects.requireNonNull(preference, "preference"));
      yieldWindowMillis.put(group, Math.max(0L, yieldWindow.toMillis()));
      if (autoMove) {
        autoMoveGroups.add(group);
      } else {
        autoMoveGroups.remove(group);
      }
    }
  }

  @Override
  public Mono<Boolean> promote(String group) {
    return Mono.fromCallable(() -> doPromote(group));
  }

  private boolean doPromote(String group) {
    synchronized (lock) {
      if (ledEpoch.containsKey(group)) {
        return true; // already Active
      }
      if (!roundInFlight.add(group)) {
        return false; // a round is mid-flight for this group; not promoted this call
      }
    }
    try {
      final long now = clock.getAsLong();
      final LeaseRecord cur = store.get(group).orElse(null); // off the lock
      if (cur != null && !cur.isExpired(now) && !cur.owner().equals(memberId())) {
        return false; // cooperative: never preempt a valid lease
      }
      final long newEpoch = (cur == null) ? 1L : cur.epoch() + 1L;
      final LeaseRecord next = new LeaseRecord(group, memberId(), newEpoch, now + leaseTtlMillis);
      final boolean won = store.compareAndSet(group, cur, next); // off the lock
      if (won) {
        synchronized (lock) {
          ledEpoch.put(group, newEpoch);
          holdOnly.add(group); // hold (renew) but do not autonomously re-acquire if lost
          lastPreference.put(group, preferenceOf(group));
          emit(group, true, newEpoch);
        }
        return true;
      }
      return false;
    } finally {
      synchronized (lock) {
        roundInFlight.remove(group);
      }
    }
  }

  @Override
  public Mono<Void> demote(String group) {
    return Mono.fromRunnable(
        () -> {
          synchronized (lock) {
            holdOnly.remove(group);
            campaigning.remove(group);
          }
          releaseIfHeld(group); // store I/O happens off the lock
        });
  }

  @Override
  public Flux<Leadership> leadership(String group) {
    final Sinks.Many<Leadership> s;
    synchronized (lock) {
      s = sink(group);
    }
    return s.asFlux().onBackpressureBuffer();
  }

  @Override
  public Optional<Member> currentLeader(String group) {
    final long now = clock.getAsLong();
    return store.get(group)
        .filter(r -> !r.isExpired(now))
        .flatMap(r -> memberResolver.apply(r.owner()));
  }

  /**
   * Drives one round of acquisitions/renewals. Call periodically (or via {@link #start(Duration)});
   * exposed for deterministic tests.
   */
  public void tick() {
    final List<String> active;
    synchronized (lock) {
      final Set<String> set = new HashSet<>(campaigning);
      set.addAll(holdOnly);
      active = new ArrayList<>(set);
    }
    // Each round below locks only to plan and commit — the store I/O runs with the lock released.
    for (String group : active) {
      final boolean led;
      synchronized (lock) {
        led = ledEpoch.containsKey(group);
      }
      if (led) {
        renew(group);
        maybeAutoMove(group); // hand off once if we became non-preferred (ADR-0016 §4.3)
      } else {
        final boolean stillCampaigning;
        synchronized (lock) {
          stillCampaigning = campaigning.contains(group);
        }
        if (stillCampaigning) {
          tryAcquire(group); // autonomous (Mode A); a promoted-only group stays passive if lost
        }
      }
    }
  }

  /**
   * Starts periodic ticking on a daemon thread so leases are renewed and standbys promoted.
   *
   * @param interval tick interval (should be well below the lease TTL)
   */
  public void start(Duration interval) {
    synchronized (lock) {
      if (ticker != null) {
        return;
      }
      ticker =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "prism-elector-" + memberId());
                t.setDaemon(true);
                return t;
              });
      long millis = interval.toMillis();
      ticker.scheduleAtFixedRate(this::tick, millis, millis, TimeUnit.MILLISECONDS);
    }
  }

  /** Stops periodic ticking (the node stops renewing; its leases will expire). */
  public void stop() {
    synchronized (lock) {
      if (ticker != null) {
        ticker.shutdownNow();
        ticker = null;
      }
    }
  }

  // ================================================
  // ============== Internal ========================
  // ================================================

  /**
   * One acquisition round. The blocking store reads/writes run with the lock <b>released</b>; only
   * the plan and the commit take the lock. {@link #roundInFlight} ensures at most one round per
   * group runs at a time, so a concurrent ticker and {@code campaign()} don't double-contend.
   */
  private void tryAcquire(String group) {
    synchronized (lock) {
      if (!roundInFlight.add(group)) {
        return; // a round for this group is already running off the lock
      }
    }
    try {
      final LeaseRecord cur = store.get(group).orElse(null); // off the lock
      final Plan plan = planContend(group, cur);
      if (plan != null) {
        contend(group, cur, plan);
      }
    } finally {
      synchronized (lock) {
        roundInFlight.remove(group);
      }
    }
  }

  /**
   * Plan phase (under the lock): given the current lease just read off the lock, decide whether to
   * contend. Handles the "we already hold it" adoption (e.g. after restart) and the
   * backoff/preference/yield gates. Returns the reserved ballot + preference to ACCEPT with, or
   * {@code null} to abort this round.
   */
  private Plan planContend(String group, LeaseRecord cur) {
    synchronized (lock) {
      final long now = clock.getAsLong();
      if (cur != null && !cur.isExpired(now)) {
        if (cur.owner().equals(memberId()) && ledEpoch.put(group, cur.epoch()) == null) {
          lastPreference.put(group, preferenceOf(group));
          emit(group, true, cur.epoch()); // we already hold it (e.g. after restart)
        }
        acquirableSince.remove(group); // a valid lease is held — reset the yield clock
        return null; // held by a valid lease — do not contend
      }

      // Anti-dueling backoff: after a contended Paxos round, wait out a randomized cooldown so one
      // proposer pulls ahead instead of all preempting each other's accepts in lockstep.
      if (now < backoffUntil.getOrDefault(group, 0L)) {
        return null;
      }

      // The lease is free/expired — consult this node's election-time preference (ADR-0016).
      final Preference pref = preferenceOf(group);
      if (pref == Preference.INELIGIBLE) {
        acquirableSince.remove(group);
        return null; // never campaigns
      }
      if (pref == Preference.STANDBY) {
        acquirableSince.putIfAbsent(group, now);
        if (now - acquirableSince.get(group) < yieldWindowMillis.getOrDefault(group, 0L)) {
          return null; // still yielding, so a PREFERRED candidate can win first
        }
      }
      return new Plan(nextBallot(group), pref);
    }
  }

  /**
   * Contend phase: Paxos PREPARE then ACCEPT, both off the lock; shared state is touched only in
   * the short commit blocks. If the lease is no longer wanted by the time we win (a concurrent
   * {@code resign} during the round), the just-won lease is released so we never leak a believer.
   */
  private void contend(String group, LeaseRecord cur, Plan plan) {
    final long ballot = plan.ballot;

    // Paxos phase 1 — PREPARE a unique, monotonically-increasing ballot across the quorum.
    final var prepare = store.prepare(group, ballot); // off the lock
    final long now = clock.getAsLong();
    synchronized (lock) {
      // Leap our counter above the highest promised/accepted ballot any acceptor reported, so the
      // next attempt is strictly higher than the floor (Paxos liveness — else we climb by 1 each).
      bumpBallotCounter(group, prepare.highestPromised());
      prepare.highestAccepted().ifPresent(h -> bumpBallotCounter(group, h.epoch()));
      if (!prepare.majorityPromised()) {
        backoff(group, now); // a higher ballot was promised, or no majority reachable — retry
        return;
      }
      // Respect a still-valid claim of another owner (Paxos value rule + stickiness): yield to it.
      final LeaseRecord highest = prepare.highestAccepted().orElse(null);
      if (highest != null && !highest.isExpired(now) && !highest.owner().equals(memberId())) {
        backoff(group, now);
        return;
      }
    }

    // Paxos phase 2 — ACCEPT ourselves at the prepared ballot.
    final LeaseRecord next = new LeaseRecord(group, memberId(), ballot, now + leaseTtlMillis);
    final boolean won = store.compareAndSet(group, cur, next); // off the lock
    boolean undo = false;
    synchronized (lock) {
      if (won) {
        if (campaigning.contains(group) || holdOnly.contains(group)) {
          ledEpoch.put(group, ballot);
          acquirableSince.remove(group);
          backoffUntil.remove(group);
          lastPreference.put(group, plan.pref);
          emit(group, true, ballot);
        } else {
          undo = true; // resigned during the round — release what we just won
        }
      } else {
        backoff(group, now); // our ballot was outbid between prepare and accept
      }
    }
    if (undo) {
      // Write an already-expired lease (release) so a standby can take over without a TTL wait.
      store.compareAndSet(group, next, new LeaseRecord(group, memberId(), ballot, now));
    }
  }

  /** A reserved ballot plus the preference to ACCEPT with, carried from plan to commit. */
  private static final class Plan {
    private final long ballot;
    private final Preference pref;

    private Plan(long ballot, Preference pref) {
      this.ballot = ballot;
      this.pref = pref;
    }
  }

  /** A unique, monotone Paxos ballot: high bits a counter, low bits this node's tag. */
  private long nextBallot(String group) {
    final long counter = ballotCounter.merge(group, 1L, Long::sum);
    return (counter << 20) | ballotTag;
  }

  /** Floors our ballot counter to at least the counter of an observed ballot (stay ahead). */
  private void bumpBallotCounter(String group, long observedBallot) {
    ballotCounter.merge(group, observedBallot >>> 20, Long::max);
  }

  private void backoff(String group, long now) {
    backoffUntil.put(group, now + acquireBackoffMillis.getAsLong());
  }

  /**
   * Releases the lease if this node currently holds it, emitting a revocation. Release is expressed
   * as an <b>already-expired lease for the same owner+epoch</b> — the protocol-native way to step
   * down (the acceptor has no "delete"): a majority immediately sees the lease as free so a standby
   * can take over without waiting for the TTL, while the fencing epoch is preserved. Works on both
   * the in-memory and the distributed quorum store (the latter cannot clear via {@code null}).
   */
  private void releaseIfHeld(String group) {
    final long epoch;
    synchronized (lock) {
      final Long e = ledEpoch.remove(group);
      acquirableSince.remove(group);
      if (e == null) {
        return;
      }
      epoch = e;
    }
    // Drop ledEpoch under the lock above so a concurrent renew's commit guard no-ops; the release
    // write and the revocation emit happen off the lock.
    final long now = clock.getAsLong();
    final LeaseRecord cur = store.get(group).orElse(null); // off the lock
    if (cur != null && cur.owner().equals(memberId()) && cur.epoch() == epoch) {
      final LeaseRecord released = new LeaseRecord(group, memberId(), epoch, now); // expired now
      store.compareAndSet(group, cur, released); // off the lock
    }
    synchronized (lock) {
      emit(group, false, epoch);
    }
  }

  /**
   * ADR-0016 §4.3 — if this leader was preferred and is no longer (the anchor's locality changed),
   * step down once so the now-preferred candidate can take over. Fires only on the
   * preferred→non-preferred edge while leading; a returning preferred node never triggers it.
   */
  private void maybeAutoMove(String group) {
    final boolean stepDown;
    synchronized (lock) {
      if (!autoMoveGroups.contains(group) || !ledEpoch.containsKey(group)) {
        return;
      }
      final Preference now = preferenceOf(group);
      final Preference last = lastPreference.put(group, now);
      stepDown = last == Preference.PREFERRED && now != Preference.PREFERRED;
    }
    if (stepDown) {
      releaseIfHeld(group); // store I/O happens off the lock
    }
  }

  private Preference preferenceOf(String group) {
    final Supplier<Preference> supplier = preferences.get(group);
    if (supplier == null) {
      return Preference.STANDBY; // default: plain election (yield window defaults to 0)
    }
    final Preference p = supplier.get();
    return p == null ? Preference.STANDBY : p;
  }

  /**
   * One renewal round, store I/O off the lock (see {@link #tryAcquire}). The {@code ledEpoch ==
   * epoch} guards make the commit a no-op if leadership changed during the round (e.g. a concurrent
   * resign), and if we renew a lease that is no longer wanted we release it.
   */
  private void renew(String group) {
    final long epoch;
    synchronized (lock) {
      final Long e = ledEpoch.get(group);
      if (e == null) {
        return; // not (or no longer) leading this group
      }
      if (!roundInFlight.add(group)) {
        return; // a round for this group is already running off the lock
      }
      epoch = e;
    }
    try {
      final long now = clock.getAsLong();
      final LeaseRecord cur = store.get(group).orElse(null); // off the lock

      if (cur == null || cur.epoch() != epoch || !cur.owner().equals(memberId())) {
        synchronized (lock) {
          if (ledEpoch.getOrDefault(group, Long.MIN_VALUE) == epoch) {
            ledEpoch.remove(group);
            emit(group, false, epoch); // lost leadership
          }
        }
        return;
      }

      final LeaseRecord next = new LeaseRecord(group, memberId(), epoch, now + leaseTtlMillis);
      final boolean ok = store.compareAndSet(group, cur, next); // off the lock
      boolean undo = false;
      synchronized (lock) {
        if (ok) {
          if (ledEpoch.getOrDefault(group, Long.MIN_VALUE) != epoch) {
            undo = true; // released/changed during the round — don't keep the lease alive
          }
        } else if (ledEpoch.getOrDefault(group, Long.MIN_VALUE) == epoch) {
          ledEpoch.remove(group);
          emit(group, false, epoch);
        }
      }
      if (undo) {
        store.compareAndSet(group, next, new LeaseRecord(group, memberId(), epoch, now));
      }
    } finally {
      synchronized (lock) {
        roundInFlight.remove(group);
      }
    }
  }

  private void emit(String group, boolean active, long epoch) {
    metrics.increment(active ? "prism.elector.granted" : "prism.elector.revoked");
    sink(group).emitNext(new LeadershipImpl(group, localMember, epoch, active), EMIT);
  }

  private Sinks.Many<Leadership> sink(String group) {
    return sinks.computeIfAbsent(group, g -> Sinks.many().multicast().directBestEffort());
  }

  private String memberId() {
    return localMember.id();
  }
}
