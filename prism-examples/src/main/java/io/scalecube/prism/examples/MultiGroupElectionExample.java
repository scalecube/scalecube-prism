package io.scalecube.prism.examples;

import io.scalecube.cluster.Member;
import io.scalecube.prism.consensus.ConsensusStore;
import io.scalecube.prism.consensus.InMemoryConsensusStore;
import io.scalecube.prism.elector.impl.LeaseElector;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A <b>group is a logical thing</b>, not a separate cluster. Here <b>one</b> cluster of five nodes
 * (n1..n5) shares a single membership + consensus substrate. "service-A" and "service-B" are just
 * logical group labels a node opts into by <i>campaigning</i> for them:
 *
 * <ul>
 *   <li>service-A is served by n1, n2, n3
 *   <li>service-B is served by n3, n4, n5
 *   <li>n3 participates in <b>both</b> groups (follower in one, leader in the other)
 * </ul>
 *
 * <p>Each group elects its own leader independently on the same machinery — different keys in one
 * store. Crashing one group's leader fails over <i>only</i> that group. Single-JVM, in-memory
 * consensus demo; the per-group logic is identical on the distributed quorum store.
 */
public final class MultiGroupElectionExample {

  private static final String SERVICE_A = "service-A";
  private static final String SERVICE_B = "service-B";

  /**
   * Runs the example.
   *
   * @param args ignored
   * @throws InterruptedException if interrupted while waiting for election/failover
   */
  public static void main(String[] args) throws InterruptedException {
    // ONE cluster: one consensus substrate shared by every node. A "group" is a key in it.
    ConsensusStore store = new InMemoryConsensusStore();
    Map<String, Member> members = new ConcurrentHashMap<>();
    Function<String, Optional<Member>> resolver = id -> Optional.ofNullable(members.get(id));

    Map<String, LeaseElector> cluster = new LinkedHashMap<>();
    for (String id : new String[] {"n1", "n2", "n3", "n4", "n5"}) {
      cluster.put(id, elector(id, store, resolver, members));
    }

    // Nodes opt into logical groups by campaigning. n3 opts into BOTH.
    // NOTE: leadership is first-come-then-sticky (ADR-0012): the first node to campaign for a free
    // group acquires the lease and then holds it. So initial leaders are decided by campaign order,
    // not a random race — that is the stickiness guarantee, not a quirk. The genuinely contested
    // election is the FAILOVER below, where the survivors race via their tickers to take over.
    join(cluster, SERVICE_A, "n1", "n2", "n3"); // n1 acquires service-A first
    join(cluster, SERVICE_B, "n3", "n4", "n5"); // n3 acquires service-B first
    cluster.values().forEach(e -> e.start(Duration.ofMillis(200)));
    Thread.sleep(400);

    System.out.println("one cluster of " + cluster.size() + " nodes; two logical groups:");
    System.out.println("  " + SERVICE_A + " leader = " + leader(store, members, SERVICE_A)
        + "   (from n1,n2,n3)");
    System.out.println("  " + SERVICE_B + " leader = " + leader(store, members, SERVICE_B)
        + "   (from n3,n4,n5; n3 is in both groups)");

    // Genuinely contested election: with the leader gone, the surviving service-A nodes all tick
    // every 200ms and race to acquire the expired lease — whichever's tick lands first wins.
    System.out.println("\n-- service-A's leader crashes; survivors race; service-B unaffected --");
    String fallenA = leader(store, members, SERVICE_A);
    cluster.get(fallenA).stop(); // that one node stops renewing
    Thread.sleep(1500);

    String newA = leader(store, members, SERVICE_A);
    String sameB = leader(store, members, SERVICE_B);
    System.out.println("  " + SERVICE_A + " leader = " + newA + " (new)");
    System.out.println("  " + SERVICE_B + " leader = " + sameB + " (same — fully independent)");

    cluster.values().forEach(LeaseElector::stop);
  }

  private static void join(Map<String, LeaseElector> cluster, String group, String... nodes) {
    for (String id : nodes) {
      cluster.get(id).campaign(group).block();
    }
  }

  private static LeaseElector elector(
      String id, ConsensusStore store, Function<String, Optional<Member>> resolver,
      Map<String, Member> members) {
    Member m = new Member(id, null, id + "@local", "prism");
    members.put(id, m);
    return new LeaseElector(m, store, resolver, Duration.ofSeconds(1), System::currentTimeMillis);
  }

  private static String leader(
      ConsensusStore store, Map<String, Member> members, String group) {
    long now = System.currentTimeMillis();
    return store.get(group)
        .filter(lease -> !lease.isExpired(now))
        .map(lease -> lease.owner())
        .filter(members::containsKey)
        .orElse("none");
  }
}
