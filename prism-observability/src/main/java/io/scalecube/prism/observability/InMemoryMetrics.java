package io.scalecube.prism.observability;

import io.scalecube.prism.metrics.Metrics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread-safe, in-memory {@link Metrics} implementation — for tests, introspection, and as a
 * reference adapter. Counters and gauges are readable via {@link #count} and {@link #gauge}.
 */
public final class InMemoryMetrics implements Metrics {

  private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
  private final Map<String, Long> gauges = new ConcurrentHashMap<>();

  @Override
  public void increment(String name) {
    counters.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
  }

  @Override
  public void gauge(String name, long value) {
    gauges.put(name, value);
  }

  /**
   * Current value of a counter.
   *
   * @param name counter name
   * @return the count, or 0 if never incremented
   */
  public long count(String name) {
    AtomicLong c = counters.get(name);
    return c == null ? 0L : c.get();
  }

  /**
   * Last value recorded for a gauge.
   *
   * @param name gauge name
   * @return the gauge value, or 0 if never set
   */
  public long gaugeValue(String name) {
    return gauges.getOrDefault(name, 0L);
  }
}
