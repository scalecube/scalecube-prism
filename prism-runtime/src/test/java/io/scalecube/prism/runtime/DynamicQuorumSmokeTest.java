package io.scalecube.prism.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.prism.Prism;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smoke test that the self-electing-quorum path ({@link PrismConfig#withDynamicQuorum(int)}) wires up
 * and runs end-to-end through {@link PrismImpl} over a real transport: a single-node dynamic quorum
 * starts, the reconfiguration loop runs without error, and the elector still safely elects a leader.
 *
 * <p>Single node by design — it isolates the wiring (config + manager + control-group + liveness
 * probe + ticker) from multi-node network timing, which the transport- and simulation-level tests
 * cover. C0 = {self}, target 1 ⇒ already healthy, so no reconfiguration is needed; the point is that
 * the dynamic machinery starts cleanly and does not break election.
 */
@DisplayName("Self-electing quorum: PrismImpl wiring starts and elects")
class DynamicQuorumSmokeTest {

  /**
   * Given a single node configured with {@code withDynamicQuorum(1)},
   * When prism starts and the node campaigns for a user group,
   * Then it becomes that group's leader and the dynamic-quorum loop runs without error.
   */
  @Test
  void dynamicQuorumStartsAndElects() throws Exception {
    String address = "127.0.0.1:" + freePort();
    PrismConfig config =
        new PrismConfig(address, List.of(address), TcpTransportFactory::new).withDynamicQuorum(1);

    Prism prism =
        new PrismImpl(new ClusterImpl().transportFactory(TcpTransportFactory::new), config)
            .startAwait();
    try {
      prism.elector().campaign("orders").block();
      Thread.sleep(1500); // let the elector and the reconfiguration ticker run a few rounds

      assertTrue(
          prism.elector().currentLeader("orders").isPresent(),
          "the dynamic-quorum node must elect a leader for the user group");
      assertEquals(
          prism.member().id(),
          prism.elector().currentLeader("orders").orElseThrow().id(),
          "the sole node leads its own group");
    } finally {
      prism.shutdown().block();
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
