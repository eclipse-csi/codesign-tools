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

/** Builds fake macOS files and bundles for tests. */
final class MacOsTestFiles {

  /** Header of a thin 64-bit little-endian Mach-O file (arm64 / x86_64). */
  static final byte[] MACH_O_64 = {(byte) 0xCF, (byte) 0xFA, (byte) 0xED, (byte) 0xFE, 0, 0, 0, 1};

  /** Header of a universal Mach-O file with 2 architectures. */
  static final byte[] MACH_O_FAT = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 2};

  /** Header of a Java 21 class file, which shares the magic number of universal Mach-O. */
  static final byte[] JAVA_CLASS = {
    (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 65
  };

  private MacOsTestFiles() {}

  static Path write(Path file, byte[] header) throws IOException {
    Files.createDirectories(file.getParent());
    byte[] content = new byte[header.length + 24];
    System.arraycopy(header, 0, content, 0, header.length);
    return Files.write(file, content);
  }

  /**
   * Builds {@code Sample.app} in {@code dir}:
   *
   * <pre>
   * Sample.app/Contents/Info.plist
   * Sample.app/Contents/MacOS/Sample                          Mach-O
   * Sample.app/Contents/Frameworks/libhelper.dylib            Mach-O (universal)
   * Sample.app/Contents/Frameworks/Lib.framework/Versions/A/Lib   Mach-O
   * Sample.app/Contents/Frameworks/Lib.framework/Versions/Current -> A
   * Sample.app/Contents/Frameworks/Lib.framework/Lib          -> Versions/Current/Lib
   * Sample.app/Contents/Resources/Plugin.class                Java class
   * Sample.app/Contents/Resources/readme.txt
   * Sample.app/Contents/_CodeSignature/CodeResources
   * </pre>
   */
  static Path sampleApp(Path dir) throws IOException {
    Path app = dir.resolve("Sample.app");
    Path contents = app.resolve("Contents");
    Files.createDirectories(contents);
    Files.writeString(contents.resolve("Info.plist"), "<plist/>");
    write(contents.resolve("MacOS/Sample"), MACH_O_64);
    write(contents.resolve("Frameworks/libhelper.dylib"), MACH_O_FAT);
    Path framework = contents.resolve("Frameworks/Lib.framework");
    write(framework.resolve("Versions/A/Lib"), MACH_O_64);
    Files.createSymbolicLink(framework.resolve("Versions/Current"), Path.of("A"));
    Files.createSymbolicLink(framework.resolve("Lib"), Path.of("Versions/Current/Lib"));
    write(contents.resolve("Resources/Plugin.class"), JAVA_CLASS);
    Files.writeString(contents.resolve("Resources/readme.txt"), "hello");
    write(contents.resolve("_CodeSignature/CodeResources"), MACH_O_64);
    return app;
  }
}
