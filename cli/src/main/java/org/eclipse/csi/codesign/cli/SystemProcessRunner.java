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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Runs commands as child processes of this one. */
class SystemProcessRunner implements ProcessRunner {

  @Override
  public Result run(List<String> command, Map<String, String> environment)
      throws IOException, InterruptedException {
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
    builder.environment().putAll(environment);
    Process process = builder.start();
    try {
      byte[] output = process.getInputStream().readAllBytes();
      int exitCode = process.waitFor();
      return new Result(exitCode, new String(output, StandardCharsets.UTF_8));
    } finally {
      process.destroy();
    }
  }
}
