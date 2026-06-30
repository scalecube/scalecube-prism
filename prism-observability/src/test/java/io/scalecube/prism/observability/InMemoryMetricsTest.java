package io.scalecube.prism.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("In-memory metrics: counters & gauges")
class InMemoryMetricsTest {

  /**
   * Given a metrics sink,
   * When a counter is incremented several times,
   * Then its value reflects the number of increments (and unknown counters read as zero).
   */
  @Test
  void countersAccumulate() {
    InMemoryMetrics metrics = new InMemoryMetrics();
    metrics.increment("prism.elector.granted");
    metrics.increment("prism.elector.granted");
    metrics.increment("prism.elector.revoked");

    assertEquals(2, metrics.count("prism.elector.granted"));
    assertEquals(1, metrics.count("prism.elector.revoked"));
    assertEquals(0, metrics.count("never.touched"));
  }

  /**
   * Given a gauge,
   * When set repeatedly,
   * Then it reads back the last value.
   */
  @Test
  void gaugeReadsLastValue() {
    InMemoryMetrics metrics = new InMemoryMetrics();
    metrics.gauge("prism.registry.size", 5);
    metrics.gauge("prism.registry.size", 9);

    assertEquals(9, metrics.gaugeValue("prism.registry.size"));
  }
}
