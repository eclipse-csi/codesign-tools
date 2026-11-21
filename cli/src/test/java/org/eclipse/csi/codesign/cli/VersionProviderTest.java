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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class VersionProviderTest {

  /**
   * A release version (0.8.5), a development version (0.8.5-dev18+g1a2b3c4), or the Maven version
   * when the git versioning extension is disabled (0.0.0-SNAPSHOT). An unfiltered
   * {@code @codesign.build.version@} placeholder does not match.
   */
  private static final Pattern VERSION_LINE =
      Pattern.compile("codesign \\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.]+)?(?:\\+[0-9A-Za-z.]+)?");

  @Test
  void reportsTheBuildVersionFilteredIntoVersionProperties() throws Exception {
    String[] lines = new VersionProvider().getVersion();

    assertEquals(1, lines.length);
    assertTrue(
        VERSION_LINE.matcher(lines[0]).matches(), () -> "unexpected version line: " + lines[0]);
  }
}
