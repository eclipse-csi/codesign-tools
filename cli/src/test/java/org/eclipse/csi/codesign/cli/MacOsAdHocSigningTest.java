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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Signs a real bundle with the real {@code codesign}, ad hoc, so that no certificate is needed:
 * checks that the inside-out order and the options produce a bundle that passes strict
 * verification.
 */
@EnabledOnOs(OS.MAC)
class MacOsAdHocSigningTest {

  private static final String ENTITLEMENTS =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
      <plist version="1.0">
      <dict>
          <key>com.apple.security.cs.allow-jit</key>
          <true/>
      </dict>
      </plist>
      """;

  private static final String INFO_PLIST =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
      <plist version="1.0">
      <dict>
          <key>CFBundleExecutable</key><string>Sample</string>
          <key>CFBundleIdentifier</key><string>org.eclipse.csi.codesign.sample</string>
          <key>CFBundlePackageType</key><string>APPL</string>
      </dict>
      </plist>
      """;

  @TempDir Path tempDir;

  @Test
  void adHocSignedBundle_passesStrictVerification() throws Exception {
    Path app = tempDir.resolve("Sample.app");
    Path contents = app.resolve("Contents");
    Files.createDirectories(contents.resolve("MacOS"));
    Files.createDirectories(contents.resolve("Frameworks"));
    Files.writeString(contents.resolve("Info.plist"), INFO_PLIST);
    // Any Mach-O file will do: codesign does not care whether a dylib is really a library.
    Files.copy(Path.of("/bin/echo"), contents.resolve("MacOS/Sample"));
    Files.copy(Path.of("/bin/echo"), contents.resolve("Frameworks/libhelper.dylib"));
    Path entitlements = Files.writeString(tempDir.resolve("app.entitlements"), ENTITLEMENTS);

    StringWriter err = new StringWriter();
    int exit =
        new CommandLine(new CodesignCli())
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(err, true))
            .setExecutionExceptionHandler(new PrintExceptionMessageHandler())
            .execute(
                "macos",
                "sign",
                "--token-provider",
                "keychain",
                "--identity",
                "-",
                "--entitlements",
                entitlements.toString(),
                "--force-overwrite",
                app.toString());
    assertEquals(0, exit, err.toString());

    SystemProcessRunner runner = new SystemProcessRunner();
    ProcessRunner.Result verify =
        runner.run(
            List.of("codesign", "--verify", "--deep", "--strict", "--verbose=2", app.toString()));
    assertEquals(0, verify.exitCode(), verify.output());

    ProcessRunner.Result nested =
        runner.run(
            List.of("codesign", "-dv", contents.resolve("Frameworks/libhelper.dylib").toString()));
    assertTrue(nested.output().contains("(adhoc,runtime)"), nested.output());

    ProcessRunner.Result top =
        runner.run(List.of("codesign", "-d", "--entitlements", "-", "--xml", app.toString()));
    assertTrue(top.output().contains("com.apple.security.cs.allow-jit"), top.output());

    ProcessRunner.Result helperEntitlements =
        runner.run(
            List.of(
                "codesign",
                "-d",
                "--entitlements",
                "-",
                "--xml",
                contents.resolve("Frameworks/libhelper.dylib").toString()));
    assertTrue(!helperEntitlements.output().contains("allow-jit"), helperEntitlements.output());
  }
}
