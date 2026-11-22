/*
 * Copyright (c) 2026 Eclipse Foundation AISBL and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.csi.codesign.cli;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Runs commands as child processes of this one. */
class SystemProcessRunner implements ProcessRunner {

  /** How often a timed run checks its stop condition. */
  private static final Duration CHECK_INTERVAL = Duration.ofMillis(500);

  @Override
  public Result run(List<String> command, Map<String, String> environment)
      throws IOException, InterruptedException {
    Process process = start(command, environment);
    try {
      byte[] output = process.getInputStream().readAllBytes();
      int exitCode = process.waitFor();
      return new Result(exitCode, new String(output, StandardCharsets.UTF_8));
    } finally {
      process.destroy();
    }
  }

  @Override
  public Result run(List<String> command, Map<String, String> environment, Duration timeout)
      throws IOException, InterruptedException {
    return run(command, environment, timeout, () -> false);
  }

  @Override
  public Result run(
      List<String> command, Map<String, String> environment, Duration timeout, BooleanSupplier stop)
      throws IOException, InterruptedException {
    Process process = start(command, environment);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    // The output is read on another thread, so that a silent process cannot block the timeout.
    Thread reader =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (InputStream in = process.getInputStream()) {
                    in.transferTo(output);
                  } catch (IOException e) {
                    // The process was stopped: keep the output so far.
                  }
                });
    try {
      long deadline = System.nanoTime() + timeout.toNanos();
      boolean finished = false;
      while (!finished) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || stop.getAsBoolean()) {
          break;
        }
        finished =
            process.waitFor(Math.min(remaining, CHECK_INTERVAL.toNanos()), TimeUnit.NANOSECONDS);
      }
      if (!finished) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
      reader.join(Duration.ofSeconds(5));
      String text;
      synchronized (output) {
        text = output.toString(StandardCharsets.UTF_8);
      }
      return finished ? new Result(process.exitValue(), text) : new Result(-1, text, true);
    } finally {
      process.destroyForcibly();
    }
  }

  private static Process start(List<String> command, Map<String, String> environment)
      throws IOException {
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
    builder.environment().putAll(environment);
    return builder.start();
  }
}
