package io.scalecube.prism.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.scalecube.cluster.Member;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Self-electing quorum: roster derived from cluster-gossip metadata")
class MetadataRosterTest {

  private static Member member(String id) {
    return new Member(id, null, id + "@cluster", "prism");
  }

  /**
   * Given cluster members that advertise their consensus address under the metadata key,
   * When the roster is resolved,
   * Then it is exactly those addresses, sorted and de-duplicated.
   */
  @Test
  void resolvesAdvertisedConsensusAddresses() {
    List<Member> members = List.of(member("a"), member("b"), member("c"));
    Map<String, Map<String, String>> md =
        Map.of(
            "a", Map.of(MetadataRoster.CONSENSUS_ADDRESS_KEY, "h:7003"),
            "b", Map.of(MetadataRoster.CONSENSUS_ADDRESS_KEY, "h:7001"),
            "c", Map.of(MetadataRoster.CONSENSUS_ADDRESS_KEY, "h:7002"));
    Function<Member, Optional<Map<String, String>>> reader =
        m -> Optional.ofNullable(md.get(m.id()));

    assertEquals(
        List.of("h:7001", "h:7002", "h:7003"),
        MetadataRoster.consensusAddresses(members, reader),
        "sorted, de-duplicated advertised addresses");
  }

  /**
   * Given some members that have not advertised an address (no metadata, wrong/blank value),
   * When the roster is resolved,
   * Then those members are skipped — a node never counts toward the quorum until its address is known.
   */
  @Test
  void skipsMembersThatHaveNotAdvertised() {
    List<Member> members = List.of(member("a"), member("b"), member("c"), member("d"));
    Map<String, Map<String, String>> md =
        Map.of(
            "a", Map.of(MetadataRoster.CONSENSUS_ADDRESS_KEY, "h:7001"),
            "b", Map.of("some.other.key", "irrelevant"),
            "c", Map.of(MetadataRoster.CONSENSUS_ADDRESS_KEY, "   ")); // blank
    Function<Member, Optional<Map<String, String>>> reader =
        m -> Optional.ofNullable(md.get(m.id())); // d has no metadata at all

    assertEquals(List.of("h:7001"), MetadataRoster.consensusAddresses(members, reader));
  }

  /**
   * Given no members at all,
   * When the roster is resolved,
   * Then it is empty (the caller falls back to the configured seed).
   */
  @Test
  void emptyWhenNoMembers() {
    assertEquals(List.of(), MetadataRoster.consensusAddresses(List.of(), m -> Optional.empty()));
  }
}
