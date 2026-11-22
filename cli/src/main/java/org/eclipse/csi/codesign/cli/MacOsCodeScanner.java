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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the code inside a macOS bundle and the order in which to sign it.
 *
 * <p>Apple requires bundles to be signed from the inside out: every nested Mach-O binary and every
 * nested bundle must be signed before the bundle that contains it, because the signature of a
 * bundle seals the signatures of its contents. {@code codesign --deep} does not do this reliably
 * (e.g. it ignores code outside the standard locations and cannot give each item its own options),
 * which is why notarization of non-trivial apps signed with it fails.
 */
final class MacOsCodeScanner {

  /** Extensions of the directories that {@code codesign} signs as bundles. */
  static final Set<String> BUNDLE_EXTENSIONS =
      Set.of("app", "framework", "xpc", "appex", "plugin", "bundle", "kext", "systemextension");

  /**
   * Upper bound on the architecture count of a universal (fat) Mach-O binary. Universal binaries
   * and Java class files share the {@code 0xCAFEBABE} magic number; in a class file the next 4
   * bytes hold the class file version, which is at least 45 (Java 1.1).
   */
  private static final int MAX_FAT_ARCHITECTURES = 30;

  private static final Set<Integer> THIN_MAGICS =
      Set.of(
          0xFEEDFACE, // 32-bit, big-endian
          0xFEEDFACF, // 64-bit, big-endian
          0xCEFAEDFE, // 32-bit, little-endian
          0xCFFAEDFE); // 64-bit, little-endian

  private static final int FAT_MAGIC = 0xCAFEBABE;
  private static final int FAT_MAGIC_64 = 0xCAFEBABF;

  private MacOsCodeScanner() {}

  /** Whether {@code path} is a directory that {@code codesign} signs as a bundle. */
  static boolean isBundle(Path path) {
    return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        && BUNDLE_EXTENSIONS.contains(extension(path));
  }

  /** Whether {@code path} is a regular file that starts like a Mach-O binary. */
  static boolean isMachO(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    byte[] header = new byte[8];
    int read;
    try (InputStream in = Files.newInputStream(path)) {
      read = in.readNBytes(header, 0, header.length);
    }
    if (read < header.length) {
      return false;
    }
    ByteBuffer buffer = ByteBuffer.wrap(header); // big-endian
    int magic = buffer.getInt();
    if (THIN_MAGICS.contains(magic)) {
      return true;
    }
    if (magic == FAT_MAGIC || magic == FAT_MAGIC_64) {
      int architectures = buffer.getInt();
      return architectures > 0 && architectures < MAX_FAT_ARCHITECTURES;
    }
    return false;
  }

  /**
   * Lists the code in {@code bundle} in signing order: the nested Mach-O binaries and nested
   * bundles, deepest first, so that each bundle comes after everything it contains, and {@code
   * bundle} itself last.
   *
   * <p>Symbolic links are not followed, so that the code they point to (e.g. through {@code
   * Versions/Current} in a framework) is listed once, under its real path. Existing {@code
   * _CodeSignature} directories are skipped.
   */
  static List<Path> signingOrder(Path bundle) throws IOException {
    Path root = bundle.toAbsolutePath().normalize();
    List<Path> nested = new ArrayList<>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (dir.getFileName() != null
                && "_CodeSignature".equals(dir.getFileName().toString())) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            if (!dir.equals(root) && BUNDLE_EXTENSIONS.contains(extension(dir))) {
              nested.add(dir);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (attrs.isRegularFile() && isMachO(file)) {
              nested.add(file);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    nested.sort(
        Comparator.comparingInt(Path::getNameCount)
            .reversed()
            .thenComparing(Path::toString, Comparator.naturalOrder()));
    nested.add(root);
    return nested;
  }

  private static String extension(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      return "";
    }
    String name = fileName.toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }
}
