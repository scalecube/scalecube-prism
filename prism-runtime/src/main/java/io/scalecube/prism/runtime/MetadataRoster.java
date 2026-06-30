package io.scalecube.prism.runtime;

import io.scalecube.cluster.Member;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Derives the self-electing-quorum roster from cluster-gossip metadata (ADR-0015). Each node
 * advertises its consensus-transport address under {@link #CONSENSUS_ADDRESS_KEY} in its scalecube
 * member metadata; peers read it from {@code cluster.members()} to discover who can join — so the
 * quorum forms and heals from the live cluster instead of a hand-listed member set.
 *
 * <p>Pure and side-effect-free so the mapping is unit-testable without a live cluster. Members that
 * have not (yet) advertised an address are simply skipped (conservative: a node never counts toward
 * the quorum until its consensus address is known).
 */
final class MetadataRoster {

  /** Metadata key under which each node advertises its consensus-transport address. */
  static final String CONSENSUS_ADDRESS_KEY = "sc/prism/consensus-address";

  private MetadataRoster() {}

  /**
   * The consensus addresses advertised by {@code members}, sorted and de-duplicated.
   *
   * @param members the cluster members to inspect (e.g. the currently-alive membership)
   * @param metadataReader reads a member's metadata map (empty if none / wrong type)
   * @return the advertised consensus addresses (possibly empty)
   */
  static List<String> consensusAddresses(
      Collection<Member> members, Function<Member, Optional<Map<String, String>>> metadataReader) {
    final TreeSet<String> addresses = new TreeSet<>();
    for (Member member : members) {
      metadataReader
          .apply(member)
          .map(md -> md.get(CONSENSUS_ADDRESS_KEY))
          .filter(addr -> addr != null && !addr.isBlank())
          .ifPresent(addresses::add);
    }
    return new ArrayList<>(addresses);
  }
}
