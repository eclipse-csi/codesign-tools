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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class MacOsNotarizeCommandTest {

  /** Stands in for the content of a .p8 file: multi-line, with a trailing newline. */
  private static final String KEY = "fake p8 key content\nsecond line\n";

  private static final String ACCEPTED =
      """
      {"id":"2efe2717-52ef-43a5-96dc-0797e4ca1041","status":"Accepted","message":"Processing complete","name":"Sample.app.zip"}
      """;
  private static final String INVALID =
      """
      Conducting pre-submission checks for Sample.dmg and initiating connection to the Apple notary service...
      {"id":"4f0d7c2a-1111-2222-3333-444455556666","status":"Invalid","message":"Processing complete","name":"Sample.dmg"}
      """;
  private static final String LOG =
      """
      {
        "jobId": "4f0d7c2a-1111-2222-3333-444455556666",
        "status": "Invalid",
        "issues": [
          {
            "severity": "error",
            "path": "Sample.dmg/Sample.app/Contents/Frameworks/libhelper.dylib",
            "message": "The signature does not include a secure timestamp.",
            "architecture": "arm64"
          }
        ]
      }
      """;

  @TempDir Path tempDir;

  private final FakeProcessRunner runner = new FakeProcessRunner();
  private final Map<String, String> environment = new HashMap<>();
  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();

  private CommandLine cli() {
    MacOsNotarizeCommand command =
        new MacOsNotarizeCommand() {
          @Override
          boolean isMacOs() {
            return true;
          }

          @Override
          ProcessRunner processRunner() {
            return runner;
          }

          @Override
          String getenv(String name) {
            return environment.get(name);
          }
        };
    return new CommandLine(
            new CodesignCli(),
            new CommandLine.IFactory() {
              @Override
              @SuppressWarnings("unchecked")
              public <K> K create(Class<K> cls) throws Exception {
                return cls == MacOsNotarizeCommand.class
                    ? (K) command
                    : CommandLine.defaultFactory().create(cls);
              }
            })
        .setOut(new PrintWriter(out, true))
        .setErr(new PrintWriter(err, true))
        .setExecutionExceptionHandler(new PrintExceptionMessageHandler());
  }

  private int notarize(String... args) {
    List<String> all = new ArrayList<>(List.of("macos", "notarize"));
    all.addAll(List.of(args));
    return cli().execute(all.toArray(String[]::new));
  }

  @Test
  void app_withApiKeyFromEnvironment_isZippedSubmittedStapledAndAssessed() throws IOException {
    Path app = MacOsTestFiles.sampleApp(tempDir).toAbsolutePath().normalize();
    environment.put("CSI_CODESIGN_NOTARY_KEY", KEY);
    environment.put("CSI_CODESIGN_NOTARY_KEY_ID", "ABC123DEFG");
    environment.put("CSI_CODESIGN_NOTARY_ISSUER", "69a6de7e-0000-47e3-e053-5b8c7c11a4d1");
    AtomicReference<Path> keyFile = new AtomicReference<>();
    runner.on(
        List.of("xcrun", "notarytool", "submit"),
        command -> {
          Path key = Path.of(command.get(command.indexOf("--key") + 1));
          keyFile.set(key);
          assertEquals(KEY, Files.readString(key));
          return new ProcessRunner.Result(0, ACCEPTED);
        });

    int exit = notarize(app.toString());

    assertEquals(0, exit, err.toString());
    List<List<String>> commands = runner.commands();
    List<String> ditto = commands.get(0);
    assertEquals(
        List.of("ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", app.toString()),
        ditto.subList(0, 6));
    Path zip = Path.of(ditto.get(6));
    assertEquals("Sample.app.zip", zip.getFileName().toString());
    assertEquals(
        List.of(
            "xcrun",
            "notarytool",
            "submit",
            zip.toString(),
            "--wait",
            "--timeout",
            "1800s",
            "--output-format",
            "json",
            "--key",
            keyFile.get().toString(),
            "--key-id",
            "ABC123DEFG",
            "--issuer",
            "69a6de7e-0000-47e3-e053-5b8c7c11a4d1"),
        commands.get(1));
    assertEquals(List.of("xcrun", "stapler", "staple", app.toString()), commands.get(2));
    assertEquals(List.of("xcrun", "stapler", "validate", app.toString()), commands.get(3));
    assertEquals(
        List.of("spctl", "--assess", "--type", "execute", "--verbose=2", app.toString()),
        commands.get(4));
    assertFalse(Files.exists(keyFile.get()), "key file deleted");
    assertTrue(
        out.toString().contains("Notarized: " + app + " (submission 2efe2717"), out.toString());
  }

  @Test
  void invalidSubmission_printsTheIssuesFromTheLogAndFails() throws IOException {
    Path dmg = Files.writeString(tempDir.resolve("Sample.dmg"), "dmg");
    runner.on(List.of("xcrun", "notarytool", "submit"), 0, INVALID);
    runner.on(List.of("xcrun", "notarytool", "log"), 0, LOG);

    int exit = notarize("--keychain-profile", "ci", dmg.toString());

    assertEquals(1, exit);
    String error = err.toString();
    assertTrue(error.contains("ended with status Invalid: Processing complete"), error);
    assertTrue(error.contains("submission 4f0d7c2a-1111-2222-3333-444455556666"), error);
    assertTrue(
        error.contains(
            "[error] Sample.dmg/Sample.app/Contents/Frameworks/libhelper.dylib (arm64): The"
                + " signature does not include a secure timestamp."),
        error);
    assertEquals(
        List.of(
            List.of(
                "xcrun",
                "notarytool",
                "log",
                "4f0d7c2a-1111-2222-3333-444455556666",
                "--keychain-profile",
                "ci")),
        runner.commands("xcrun", "notarytool", "log"));
    assertTrue(runner.commands("xcrun", "stapler").isEmpty());
  }

  @Test
  void submissionWithoutJsonAnswer_quotesTheToolOutput() throws IOException {
    Path pkg = Files.writeString(tempDir.resolve("Sample.pkg"), "pkg");
    runner.on(
        List.of("xcrun", "notarytool", "submit"),
        69,
        "Error: HTTP status code: 401. Unable to authenticate.");

    int exit = notarize("--keychain-profile", "ci", pkg.toString());

    assertEquals(1, exit);
    assertTrue(err.toString().contains("Unable to authenticate"), err.toString());
  }

  @Test
  void diskImageAndPackage_areAssessedWithTheirGatekeeperType() throws IOException {
    Path dmg = Files.writeString(tempDir.resolve("Sample.dmg"), "dmg");
    Path pkg = Files.writeString(tempDir.resolve("Sample.pkg"), "pkg");
    runner.on(List.of("xcrun", "notarytool", "submit"), 0, ACCEPTED);

    int exit = notarize("--keychain-profile", "ci", dmg.toString(), pkg.toString());

    assertEquals(0, exit, err.toString());
    assertEquals(
        List.of(
            List.of(
                "spctl",
                "--assess",
                "--type",
                "open",
                "--context",
                "context:primary-signature",
                "--verbose=2",
                dmg.toAbsolutePath().normalize().toString()),
            List.of(
                "spctl",
                "--assess",
                "--type",
                "install",
                "--verbose=2",
                pkg.toAbsolutePath().normalize().toString())),
        runner.commands("spctl"));
  }

  @Test
  void zip_isNeitherStapledNorAssessed() throws IOException {
    Path zip = Files.writeString(tempDir.resolve("Sample.zip"), "zip");
    runner.on(List.of("xcrun", "notarytool", "submit"), 0, ACCEPTED);

    int exit = notarize("--keychain-profile", "ci", zip.toString());

    assertEquals(0, exit, err.toString());
    assertTrue(runner.commands("xcrun", "stapler").isEmpty());
    assertTrue(runner.commands("spctl").isEmpty());
  }

  @Test
  void noStaple_skipsStapling() throws IOException {
    Path dmg = Files.writeString(tempDir.resolve("Sample.dmg"), "dmg");
    runner.on(List.of("xcrun", "notarytool", "submit"), 0, ACCEPTED);

    int exit = notarize("--keychain-profile", "ci", "--no-staple", dmg.toString());

    assertEquals(0, exit, err.toString());
    assertTrue(runner.commands("xcrun", "stapler").isEmpty());
    assertEquals(1, runner.commands("spctl").size());
  }

  @Test
  void gatekeeperRejection_fails() throws IOException {
    Path dmg = Files.writeString(tempDir.resolve("Sample.dmg"), "dmg");
    runner.on(List.of("xcrun", "notarytool", "submit"), 0, ACCEPTED);
    runner.on(List.of("spctl"), 3, "Sample.dmg: rejected\nsource=Unnotarized Developer ID");

    int exit = notarize("--keychain-profile", "ci", dmg.toString());

    assertEquals(1, exit);
    assertTrue(err.toString().contains("Gatekeeper rejects"), err.toString());
    assertTrue(err.toString().contains("Unnotarized Developer ID"), err.toString());
  }

  @Test
  void noCredentials_isAUsageError() throws IOException {
    Path dmg = Files.writeString(tempDir.resolve("Sample.dmg"), "dmg");

    int exit = notarize(dmg.toString());

    assertEquals(2, exit);
    assertTrue(err.toString().contains("No notarization credentials"), err.toString());
    assertTrue(runner.commands().isEmpty());
  }

  @Test
  void keychainProfile_cannotBeCombinedWithApiKeyOptions() throws IOException {
    Path dmg = Files.writeString(tempDir.resolve("Sample.dmg"), "dmg");

    int exit = notarize("--keychain-profile", "ci", "--notary-key-id", "ABC", dmg.toString());

    assertEquals(2, exit);
  }

  @Test
  void machOBinary_isSubmittedInAZipAndNeitherStapledNorAssessed() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    runner.on(List.of("xcrun", "notarytool", "submit"), 0, ACCEPTED);

    int exit = notarize("--keychain-profile", "ci", binary.toString());

    assertEquals(0, exit, err.toString());
    List<String> ditto = runner.commands("ditto").get(0);
    assertEquals(
        List.of("ditto", "-c", "-k", "--sequesterRsrc", "--keepParent"), ditto.subList(0, 5));
    assertEquals(binary.toAbsolutePath().normalize().toString(), ditto.get(5));
    String zip = ditto.get(6);
    assertTrue(zip.endsWith("tool.zip"), zip);
    assertEquals(zip, runner.commands("xcrun", "notarytool", "submit").get(0).get(3));
    assertTrue(runner.commands("xcrun", "stapler").isEmpty());
    assertTrue(runner.commands("spctl").isEmpty());
  }

  @Test
  void unsupportedInput_isAUsageError() throws IOException {
    Path text = Files.writeString(tempDir.resolve("notes.txt"), "not code");

    int exit = notarize("--keychain-profile", "ci", text.toString());

    assertEquals(2, exit);
    assertTrue(
        err.toString().contains("not a .app, .dmg, .pkg, .zip or Mach-O binary"), err.toString());
  }

  @Test
  void parseJson_ignoresTextAroundTheObject() {
    assertEquals("Invalid", MacOsNotarizeCommand.parseJson(INVALID).get("status").getAsString());
    assertNull(MacOsNotarizeCommand.parseJson("Error: no JSON here"));
    assertNull(MacOsNotarizeCommand.parseJson("{ not json }"));
  }
}
