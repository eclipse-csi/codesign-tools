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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

@DisabledOnOs(OS.WINDOWS)
class SystemProcessRunnerTest {

  private final SystemProcessRunner runner = new SystemProcessRunner();

  @Test
  void timedRun_returnsTheResultOfAProcessThatFinishes() throws Exception {
    ProcessRunner.Result result =
        runner.run(List.of("sh", "-c", "echo done; exit 3"), Map.of(), Duration.ofSeconds(30));

    assertFalse(result.timedOut());
    assertEquals(3, result.exitCode());
    assertEquals("done", result.output().strip());
  }

  @Test
  void timedRun_stopsAProcessThatRunsTooLong() throws Exception {
    long start = System.nanoTime();
    ProcessRunner.Result result =
        runner.run(List.of("sh", "-c", "echo started; sleep 30"), Map.of(), Duration.ofMillis(500));

    assertTrue(result.timedOut());
    assertEquals("started", result.output().strip());
    assertTrue(
        Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(20)) < 0,
        "stopped long before the process would have finished");
  }

  @Test
  void timedRun_stopsAProcessAsSoonAsTheStopConditionHolds() throws Exception {
    long start = System.nanoTime();
    ProcessRunner.Result result =
        runner.run(
            List.of("sleep", "30"),
            Map.of(),
            Duration.ofSeconds(30),
            () -> System.nanoTime() - start > Duration.ofMillis(300).toNanos());

    assertTrue(result.timedOut());
    assertTrue(
        Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(20)) < 0,
        "stopped long before the timeout");
  }
}
