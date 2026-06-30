package io.scalecube.prism.consensus;

import java.util.Map;

/**
 * Durable storage for an {@link Acceptor}'s accepted leases. Single-decree quorum consensus is only
 * safe if an acceptor never forgets a lease it accepted — a crash that loses state could let a
 * recovered acceptor accept a conflicting lease and break "never two leaders". The journal makes
 * acceptances <b>write-ahead durable</b> (persisted before acknowledging) and reloads them on
 * restart.
 */
public interface LeaseJournal {

  /**
   * Durably records an accepted lease before the acceptor acknowledges it.
   *
   * @param lease the accepted lease
   */
  void append(LeaseRecord lease);

  /**
   * Recovers the highest-epoch lease per group from durable storage.
   *
   * @return group → recovered lease
   */
  Map<String, LeaseRecord> load();

  /**
   * A non-durable journal (in-memory acceptors / tests).
   *
   * @return a no-op journal
   */
  static LeaseJournal noop() {
    return new LeaseJournal() {
      @Override
      public void append(LeaseRecord lease) {
        // no-op
      }

      @Override
      public Map<String, LeaseRecord> load() {
        return Map.of();
      }
    };
  }
}
