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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Creates files and directories that only the current user can access. */
final class PrivateFiles {

  private PrivateFiles() {}

  /**
   * Creates a temporary directory that only the current user can access ({@code rwx------}).
   *
   * <p>On file systems without POSIX permissions (Windows), it falls back to the default
   * permissions: the temporary directory is then that of the user, which others cannot read.
   */
  // The fallback only runs where the temporary directory is already private to the user.
  @SuppressWarnings("java:S5443")
  static Path createTempDirectory(String prefix) throws IOException {
    try {
      return Files.createTempDirectory(
          prefix,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } catch (UnsupportedOperationException e) {
      return Files.createTempDirectory(prefix);
    }
  }

  /** Creates an empty file that only the current user can read and write ({@code rw-------}). */
  static Path createFile(Path file) throws IOException {
    try {
      return Files.createFile(
          file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException e) {
      return Files.createFile(file);
    }
  }
}
