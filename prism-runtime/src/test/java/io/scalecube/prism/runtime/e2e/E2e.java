package io.scalecube.prism.runtime.e2e;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Tiny support for end-to-end, black-box tests that drive the public {@code Prism} API over real
 * transports. The elector and registry run their own timers — these tests never poke internals
 * (no {@code tick()}); they <b>await</b> observable outcomes the way a real client would.
 */
final class E2e {

  private E2e() {}

  /** An ephemeral free TCP port (for a node's dedicated consensus address). */
  static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Polls {@code condition} until true or the deadline, then fails with {@code message}. */
  static void await(Duration timeout, String message, BooleanSupplier condition) {
    final long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      sleep(100);
    }
    if (!condition.getAsBoolean()) {
      fail("timed out after " + timeout.toMillis() + "ms waiting for: " + message);
    }
  }

  /** Polls {@code value} until non-null/satisfied, returning it, or fails at the deadline. */
  static <T> T awaitValue(Duration timeout, String message, Supplier<T> value, BooleanSupplier ok) {
    await(timeout, message, ok);
    return value.get();
  }

  static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }
}
