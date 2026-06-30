package io.scalecube.prism.examples;

import io.scalecube.cluster.Member;
import io.scalecube.prism.consensus.ConsensusStore;
import io.scalecube.prism.consensus.InMemoryConsensusStore;
import io.scalecube.prism.elector.Leadership;
import io.scalecube.prism.elector.impl.LeaseElector;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Active/passive gateway: exactly one of two gateways is Active at any time. Demonstrates election,
 * failover when the Active "crashes", and fencing of the zombie old-Active.
 *
 * <p>This is a single-process demo over the in-memory consensus store (which models the consensus
 * guarantee within one JVM). A cross-node, partition-safe gateway needs the distributed
 * {@code QuorumConsensusStore} (single-decree Paxos; see {@code PrismGatewayExample} and
 * {@code DynamicQuorumExample}); the elector logic shown here is exactly what plugs onto it.
 */
public final class GatewayElectionExample {

  /**
   * Runs the example.
   *
   * @param args ignored
   * @throws InterruptedException if interrupted while waiting for renewal/failover
   */
  public static void main(String[] args) throws InterruptedException {
    ConsensusStore store = new InMemoryConsensusStore();
    Map<String, Member> members = new ConcurrentHashMap<>();
    Function<String, Optional<Member>> resolver = id -> Optional.ofNullable(members.get(id));

    LeaseElector a = elector("gw-A", store, resolver, members);
    LeaseElector b = elector("gw-B", store, resolver, members);

    GatewayServer sa = new GatewayServer();
    GatewayServer sb = new GatewayServer();
    a.leadership("gateway").subscribe(lead -> sa.apply(lead));
    b.leadership("gateway").subscribe(lead -> sb.apply(lead));

    a.campaign("gateway").block();
    b.campaign("gateway").block();
    a.start(Duration.ofMillis(200));
    b.start(Duration.ofMillis(200));
    Thread.sleep(400);

    System.out.println("after election:  A[" + sa + "]  B[" + sb + "]");

    System.out.println("\n-- A crashes (stops renewing its lease) --");
    a.stop();
    Thread.sleep(1500); // lease expires; B (still ticking) is promoted

    System.out.println("after failover:  A[" + sa + "]  B[" + sb + "]  (A is a zombie)");

    System.out.println("\n-- fencing: a shared downstream rejects the zombie's stale epoch --");
    Downstream downstream = new Downstream();
    System.out.println(
        "B  writes epoch " + sb.epoch + " -> accepted=" + downstream.accept(sb.epoch));
    System.out.println("A* writes epoch " + sa.epoch + " -> accepted=" + downstream.accept(sa.epoch)
        + "  (fenced)");

    b.stop();
  }

  private static LeaseElector elector(
      String id, ConsensusStore store, Function<String, Optional<Member>> resolver,
      Map<String, Member> members) {
    Member m = new Member(id, null, id + "@local", "prism");
    members.put(id, m);
    return new LeaseElector(m, store, resolver, Duration.ofSeconds(1), System::currentTimeMillis);
  }

  /** A toy gateway listener: open (accepting) only while Active, tagged with the fencing epoch. */
  static final class GatewayServer {
    volatile boolean active;
    volatile long epoch;

    void apply(Leadership lead) {
      this.active = lead.active();
      if (lead.active()) {
        this.epoch = lead.epoch();
      }
    }

    @Override
    public String toString() {
      return "active=" + active + " epoch=" + epoch;
    }
  }

  /** A downstream resource that fences by epoch: rejects anything older than the highest seen. */
  static final class Downstream {
    private long fence;

    synchronized boolean accept(long epoch) {
      if (epoch < fence) {
        return false;
      }
      fence = epoch;
      return true;
    }
  }
}
