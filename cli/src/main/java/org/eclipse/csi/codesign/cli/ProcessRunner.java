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

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Runs the external tools that the {@code macos} commands drive ({@code codesign}, {@code
 * productsign}, {@code security}, {@code hdiutil}, {@code xcrun}...). Tests replace it with a fake
 * that records the commands.
 */
interface ProcessRunner {

  /** Maximum number of output characters quoted in a {@link ToolException} message. */
  int MAX_QUOTED_OUTPUT = 4000;

  /**
   * Runs {@code command} to completion, with stdout and stderr merged and stdin closed.
   *
   * @param command the program and its arguments
   * @param environment variables added to the environment inherited from this process
   * @return the exit code and the output of the command
   */
  Result run(List<String> command, Map<String, String> environment)
      throws IOException, InterruptedException;

  /** Runs {@code command} with the environment of this process. */
  default Result run(List<String> command) throws IOException, InterruptedException {
    return run(command, Map.of());
  }

  /**
   * Runs {@code command} and fails when it exits with a non-zero code.
   *
   * @return the output of the command
   * @throws ToolException when the command exits with a non-zero code; the message quotes its
   *     output
   */
  default String runChecked(List<String> command, Map<String, String> environment)
      throws IOException, InterruptedException, ToolException {
    Result result = run(command, environment);
    if (result.exitCode() != 0) {
      throw new ToolException(
          String.join(" ", command.subList(0, Math.min(2, command.size())))
              + " failed with exit code "
              + result.exitCode()
              + quote(result.output()));
    }
    return result.output();
  }

  /** Runs {@code command} with the environment of this process and fails on a non-zero exit. */
  default String runChecked(List<String> command)
      throws IOException, InterruptedException, ToolException {
    return runChecked(command, Map.of());
  }

  /** Formats tool output for an error message: on its own lines, truncated if very long. */
  static String quote(String output) {
    String trimmed = output == null ? "" : output.strip();
    if (trimmed.isEmpty()) {
      return "";
    }
    if (trimmed.length() > MAX_QUOTED_OUTPUT) {
      trimmed = "..." + trimmed.substring(trimmed.length() - MAX_QUOTED_OUTPUT);
    }
    return ":\n" + trimmed;
  }

  /**
   * Outcome of a command.
   *
   * @param exitCode the exit code of the process
   * @param output stdout and stderr, merged
   */
  record Result(int exitCode, String output) {}
}
