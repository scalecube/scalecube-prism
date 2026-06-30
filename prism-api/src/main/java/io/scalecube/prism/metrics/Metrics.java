package io.scalecube.prism.metrics;

/**
 * A minimal, dependency-free metrics SPI. prism components emit counters and gauges through it;
 * deployments plug in a real backend (Micrometer/OpenTelemetry adapter) or use the in-memory
 * implementation from {@code prism-observability}. Defaults to {@link #NOOP} so metrics are
 * zero-overhead unless wired.
 */
public interface Metrics {

  /** A no-op metrics sink. */
  Metrics NOOP =
      new Metrics() {
        @Override
        public void increment(String name) {
          // no-op
        }

        @Override
        public void gauge(String name, long value) {
          // no-op
        }
      };

  /**
   * Increments a counter by one.
   *
   * @param name counter name (e.g. {@code prism.elector.granted})
   */
  void increment(String name);

  /**
   * Records a gauge value.
   *
   * @param name gauge name
   * @param value current value
   */
  void gauge(String name, long value);
}
