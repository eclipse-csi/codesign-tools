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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class MacOsSignCommandTest {

  private static final String APP_IDENTITY = "Developer ID Application: Example AISBL (TEAM123456)";
  private static final String INSTALLER_IDENTITY =
      "Developer ID Installer: Example AISBL (TEAM123456)";
  private static final String IDENTITIES =
      """
        1) 0123456789ABCDEF0123456789ABCDEF01234567 "%s"
        2) 89ABCDEF0123456789ABCDEF0123456789ABCDEF "%s"
           2 valid identities found
      """
          .formatted(APP_IDENTITY, INSTALLER_IDENTITY);
  private static final String APP_SHA1 = "0123456789ABCDEF0123456789ABCDEF01234567";
  private static final String NO_IDENTITIES = "     0 valid identities found\n";

  /**
   * {@code security find-identity} as it lists the self-signed certificate of a SignPath test
   * signing policy: macOS does not trust it, so it is missing from "Valid identities only".
   */
  private static final String UNTRUSTED_TEST_IDENTITY =
      """
      Policy: X.509 Basic
        Matching identities
        1) 1111111111111111111111111111111111111111 "Example test signing" (CSSMERR_TP_NOT_TRUSTED)
           1 identities found

        Valid identities only
           0 valid identities found
      """;

  private static final String TOKEN = "secret-api-token";

  @TempDir Path tempDir;

  private final FakeProcessRunner runner = new FakeProcessRunner();
  private final List<Duration> pauses = new ArrayList<>();
  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();
  private boolean macOs = true;

  /** What SignPath answers for the signing policy's certificate; null fails the lookup. */
  private byte[] policyCertificate;

  private final List<SignPathTokenSession.Settings> certificateLookups = new ArrayList<>();

  private CommandLine cli() {
    MacOsSignCommand command =
        new MacOsSignCommand() {
          @Override
          boolean isMacOs() {
            return macOs;
          }

          @Override
          ProcessRunner processRunner() {
            return runner;
          }

          @Override
          KeychainIdentities.Pauser pauser() {
            return pauses::add;
          }

          @Override
          String resolveToken() {
            return TOKEN;
          }

          @Override
          byte[] signingPolicyCertificate(SignPathTokenSession.Settings settings)
              throws ToolException {
            certificateLookups.add(settings);
            if (policyCertificate == null) {
              throw new ToolException("SignPath does not list the signing policy");
            }
            return policyCertificate;
          }

          @Override
          SignPathTokenSession newTokenSession(
              ProcessRunner processRunner, SignPathTokenSession.Settings settings) {
            return new SignPathTokenSession(
                processRunner,
                (url, destination) -> Files.writeString(destination, "dmg from " + url),
                message -> {},
                settings,
                tempDir.resolve("logs"),
                tempDir.resolve("containers"));
          }
        };
    return new CommandLine(
            new CodesignCli(),
            new CommandLine.IFactory() {
              @Override
              @SuppressWarnings("unchecked")
              public <K> K create(Class<K> cls) throws Exception {
                return cls == MacOsSignCommand.class
                    ? (K) command
                    : CommandLine.defaultFactory().create(cls);
              }
            })
        .setOut(new PrintWriter(out, true))
        .setErr(new PrintWriter(err, true))
        .setExecutionExceptionHandler(new PrintExceptionMessageHandler());
  }

  private int sign(String... args) {
    List<String> all = new ArrayList<>(List.of("macos", "sign"));
    all.addAll(List.of(args));
    return cli().execute(all.toArray(String[]::new));
  }

  /**
   * Makes {@code hdiutil attach} create the app at the mount point, as the real disk image does.
   */
  private void fakeDiskImage() {
    runner.on(
        List.of("hdiutil", "attach"),
        command -> {
          Path mountPoint = Path.of(command.get(command.indexOf("-mountpoint") + 1));
          Files.createDirectories(
              mountPoint
                  .resolve(SignPathTokenSession.APP_NAME)
                  .resolve("Contents/PlugIns/ctk.appex"));
          return new ProcessRunner.Result(0, "/dev/disk9 " + mountPoint);
        });
  }

  private static List<String> codesign(String identity, String... rest) {
    List<String> command =
        new ArrayList<>(List.of("codesign", "--force", "--timestamp", "--sign", identity));
    command.addAll(List.of(rest));
    return command;
  }

  // ---- keychain provider ----

  @Test
  void keychainProvider_signsBundleInsideOutWithEntitlementsOnTopLevelOnly() throws IOException {
    Path app = MacOsTestFiles.sampleApp(tempDir).toAbsolutePath().normalize();
    Path contents = app.resolve("Contents");
    Path entitlements = Files.writeString(tempDir.resolve("app.entitlements"), "<plist/>");
    runner.on(List.of("security", "find-identity"), 0, IDENTITIES);

    int exit =
        sign(
            "--token-provider",
            "keychain",
            "--identity",
            APP_IDENTITY,
            "--entitlements",
            entitlements.toString(),
            "--force-overwrite",
            app.toString());

    assertEquals(0, exit, err.toString());
    String ent = entitlements.toAbsolutePath().normalize().toString();
    assertEquals(
        List.of(
            codesign(
                APP_SHA1,
                "--options",
                "runtime",
                contents.resolve("Frameworks/Lib.framework/Versions/A/Lib").toString()),
            codesign(
                APP_SHA1,
                "--options",
                "runtime",
                contents.resolve("Frameworks/Lib.framework").toString()),
            codesign(
                APP_SHA1,
                "--options",
                "runtime",
                contents.resolve("Frameworks/libhelper.dylib").toString()),
            codesign(APP_SHA1, "--options", "runtime", contents.resolve("MacOS/Sample").toString()),
            codesign(APP_SHA1, "--options", "runtime", "--entitlements", ent, app.toString())),
        runner.commands("codesign", "--force"));
    assertEquals(
        List.of(
            List.of("codesign", "--verify", "--strict", "--deep", "--verbose=2", app.toString())),
        runner.commands("codesign", "--verify"));
    assertTrue(out.toString().contains("Signed: " + app), out.toString());
    assertTrue(runner.commands("open").isEmpty(), "no CryptoTokenKit with the keychain provider");
    assertTrue(pauses.isEmpty(), "the keychain provider does not wait for identities");
  }

  @Test
  void keychainProvider_adHocIdentity_skipsTimestampAndKeychainLookup() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);

    int exit =
        sign(
            "--token-provider",
            "keychain",
            "--identity",
            "-",
            "--force-overwrite",
            binary.toString());

    assertEquals(0, exit, err.toString());
    assertEquals(
        List.of(
            List.of(
                "codesign",
                "--force",
                "--timestamp=none",
                "--sign",
                "-",
                "--options",
                "runtime",
                binary.toAbsolutePath().normalize().toString())),
        runner.commands("codesign", "--force"));
    assertTrue(runner.commands("security").isEmpty());
  }

  @Test
  void keychainProvider_missingIdentity_failsWithoutWaiting() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    runner.on(List.of("security", "find-identity"), 0, NO_IDENTITIES);

    int exit =
        sign(
            "--token-provider",
            "keychain",
            "--identity",
            APP_IDENTITY,
            "--force-overwrite",
            binary.toString());

    assertEquals(1, exit);
    assertTrue(
        err.toString().contains("not found in the keychain. Available identities: none"),
        err.toString());
    assertTrue(pauses.isEmpty());
    assertTrue(runner.commands("codesign").isEmpty());
  }

  @Test
  void ambiguousIdentity_fails() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    runner.on(List.of("security", "find-identity"), 0, IDENTITIES);

    int exit =
        sign(
            "--token-provider",
            "keychain",
            "--identity",
            "Example AISBL",
            "--force-overwrite",
            binary.toString());

    assertEquals(1, exit);
    assertTrue(err.toString().contains("matches several signing identities"), err.toString());
  }

  @Test
  void untrustedIdentity_signsWithItAndWarns() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    runner.on(List.of("security", "find-identity"), 0, UNTRUSTED_TEST_IDENTITY);

    int exit =
        sign(
            "--token-provider",
            "keychain",
            "--identity",
            "Example test signing",
            "--force-overwrite",
            binary.toString());

    assertEquals(0, exit, err.toString());
    assertEquals(
        List.of(
            codesign(
                "1111111111111111111111111111111111111111",
                "--options",
                "runtime",
                binary.toAbsolutePath().normalize().toString())),
        runner.commands("codesign", "--force"));
    assertTrue(err.toString().contains("is not trusted on this machine"), err.toString());
    // Untrusted identities are only listed without -v.
    assertEquals(List.of(List.of("security", "find-identity")), runner.commands("security"));
  }

  @Test
  void expiredIdentityWithTheSameName_isIgnored() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    runner.on(
        List.of("security", "find-identity"),
        0,
        """
        Policy: X.509 Basic
          Matching identities
          1) 2222222222222222222222222222222222222222 "%1$s" (CSSMERR_TP_CERT_EXPIRED)
          2) %2$s "%1$s"
             2 identities found

          Valid identities only
          1) %2$s "%1$s"
             1 valid identities found
        """
            .formatted(APP_IDENTITY, APP_SHA1));

    int exit =
        sign(
            "--token-provider",
            "keychain",
            "--identity",
            APP_IDENTITY,
            "--force-overwrite",
            binary.toString());

    assertEquals(0, exit, err.toString());
    // codesign gets the SHA-1: given the name, it would reject the two certificates as ambiguous.
    assertEquals(
        List.of(
            codesign(
                APP_SHA1, "--options", "runtime", binary.toAbsolutePath().normalize().toString())),
        runner.commands("codesign", "--force"));
    assertFalse(err.toString().contains("Warning"), err.toString());
  }

  @Test
  void onlyExpiredIdentity_failsWithTheReason() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    runner.on(
        List.of("security", "find-identity"),
        0,
        """
          1) 2222222222222222222222222222222222222222 "%s" (CSSMERR_TP_CERT_EXPIRED)
             1 identities found
        """
            .formatted(APP_IDENTITY));

    int exit =
        sign(
            "--token-provider",
            "keychain",
            "--identity",
            APP_IDENTITY,
            "--force-overwrite",
            binary.toString());

    assertEquals(1, exit);
    assertTrue(err.toString().contains("Matching identities that cannot sign"), err.toString());
    assertTrue(err.toString().contains("CSSMERR_TP_CERT_EXPIRED"), err.toString());
    assertTrue(runner.commands("codesign").isEmpty());
  }

  @Test
  void diskImage_isSignedWithoutHardenedRuntimeOrEntitlements() throws IOException {
    Path dmg = Files.writeString(tempDir.resolve("Sample.dmg"), "dmg");
    Path entitlements = Files.writeString(tempDir.resolve("app.entitlements"), "<plist/>");
    runner.on(List.of("security", "find-identity"), 0, IDENTITIES);

    int exit =
        sign(
            "--token-provider",
            "keychain",
            "--identity",
            APP_IDENTITY,
            "--entitlements",
            entitlements.toString(),
            "--force-overwrite",
            dmg.toString());

    assertEquals(0, exit, err.toString());
    String path = dmg.toAbsolutePath().normalize().toString();
    assertEquals(List.of(codesign(APP_SHA1, path)), runner.commands("codesign", "--force"));
    assertEquals(
        List.of(List.of("codesign", "--verify", "--strict", "--verbose=2", path)),
        runner.commands("codesign", "--verify"));
  }

  @Test
  void installerPackage_isSignedWithProductsignAndReplacedAtomically() throws IOException {
    Path pkg = Files.writeString(tempDir.resolve("Sample.pkg"), "unsigned");
    runner.on(List.of("security", "find-identity"), 0, IDENTITIES);
    runner.on(
        List.of("productsign"),
        command -> {
          Files.writeString(Path.of(command.get(command.size() - 1)), "signed");
          return new ProcessRunner.Result(0, "");
        });

    int exit =
        sign(
            "--token-provider",
            "keychain",
            "--identity",
            APP_IDENTITY,
            "--installer-identity",
            INSTALLER_IDENTITY,
            "--force-overwrite",
            pkg.toString());

    assertEquals(0, exit, err.toString());
    String path = pkg.toAbsolutePath().normalize().toString();
    assertEquals(
        List.of(
            List.of(
                "productsign",
                "--timestamp",
                "--sign",
                INSTALLER_IDENTITY,
                path,
                tempDir.toAbsolutePath().normalize().resolve(".Sample.pkg.signed").toString())),
        runner.commands("productsign"));
    assertEquals("signed", Files.readString(pkg));
    assertFalse(Files.exists(tempDir.resolve(".Sample.pkg.signed")));
    assertEquals(
        List.of(List.of("pkgutil", "--check-signature", path)), runner.commands("pkgutil"));
  }

  @Test
  void signPathProvider_installerPackageAlone_needsNoCodeIdentity() throws IOException {
    Path pkg = Files.writeString(tempDir.resolve("Sample.pkg"), "unsigned");
    fakeDiskImage();
    runner.on(List.of("security", "find-identity"), 0, IDENTITIES);
    runner.on(
        List.of("productsign"),
        command -> {
          Files.writeString(Path.of(command.get(command.size() - 1)), "signed");
          return new ProcessRunner.Result(0, "");
        });

    int exit =
        sign(
            "-O",
            "org-id",
            "-p",
            "my-project",
            "-s",
            "installer-signing",
            "--installer-identity",
            INSTALLER_IDENTITY,
            "--force-overwrite",
            pkg.toString());

    assertEquals(0, exit, err.toString());
    assertEquals(1, runner.commands("productsign").size());
    assertTrue(runner.commands("codesign", "--force").isEmpty());
  }

  @Test
  void signPathProvider_rejectsCodeAndPackagesInOneInvocation() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    Path pkg = Files.writeString(tempDir.resolve("Sample.pkg"), "unsigned");

    int exit =
        sign(
            "-O",
            "org-id",
            "-p",
            "my-project",
            "-s",
            "release-signing",
            "--identity",
            APP_IDENTITY,
            "--installer-identity",
            INSTALLER_IDENTITY,
            "--force-overwrite",
            binary.toString(),
            pkg.toString());

    assertEquals(2, exit);
    assertTrue(err.toString().contains("separate invocation"), err.toString());
    assertTrue(runner.commands().isEmpty());
  }

  @Test
  void codeRequiresIdentity() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);

    int exit = sign("--token-provider", "keychain", "--force-overwrite", binary.toString());

    assertEquals(2, exit);
    assertTrue(err.toString().contains("requires --identity"), err.toString());
  }

  @Test
  void installerPackage_requiresInstallerIdentity() throws IOException {
    Path pkg = Files.writeString(tempDir.resolve("Sample.pkg"), "unsigned");

    int exit =
        sign(
            "--token-provider",
            "keychain",
            "--identity",
            APP_IDENTITY,
            "--force-overwrite",
            pkg.toString());

    assertEquals(2, exit);
    assertTrue(err.toString().contains("--installer-identity"), err.toString());
  }

  @Test
  void outputDir_copiesWithDittoThenSignsTheCopy() throws IOException {
    Path app = MacOsTestFiles.sampleApp(tempDir.resolve("in")).toAbsolutePath().normalize();
    Path outDir = tempDir.resolve("out").toAbsolutePath().normalize();
    runner.on(List.of("security", "find-identity"), 0, IDENTITIES);
    runner.on(
        List.of("ditto"),
        command -> {
          copyTree(Path.of(command.get(1)), Path.of(command.get(2)));
          return new ProcessRunner.Result(0, "");
        });

    int exit =
        sign(
            "--token-provider",
            "keychain",
            "--identity",
            APP_IDENTITY,
            "--output-dir",
            outDir.toString(),
            app.toString());

    assertEquals(0, exit, err.toString());
    Path copy = outDir.resolve("Sample.app");
    assertEquals(
        List.of(List.of("ditto", app.toString(), copy.toString())), runner.commands("ditto"));
    List<List<String>> signed = runner.commands("codesign", "--force");
    assertEquals(5, signed.size());
    assertTrue(
        signed.stream().allMatch(c -> c.get(c.size() - 1).startsWith(copy.toString())),
        signed.toString());
  }

  @Test
  void outputDir_existingDestination_requiresForceOverwrite() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("in/tool"), MacOsTestFiles.MACH_O_64);
    Files.createDirectories(tempDir.resolve("out"));
    Files.writeString(tempDir.resolve("out/tool"), "old");
    runner.on(List.of("security", "find-identity"), 0, IDENTITIES);

    int exit =
        sign(
            "--token-provider",
            "keychain",
            "--identity",
            APP_IDENTITY,
            "--output-dir",
            tempDir.resolve("out").toString(),
            binary.toString());

    assertEquals(2, exit);
    assertTrue(err.toString().contains("already exists"), err.toString());
  }

  // ---- signpath provider ----

  @Test
  void signPathProvider_runsCryptoTokenKitWaitsForIdentityAndTearsDown() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    fakeDiskImage();
    AtomicInteger lookups = new AtomicInteger();
    runner.on(
        List.of("security", "find-identity"),
        command ->
            new ProcessRunner.Result(
                0, lookups.incrementAndGet() < 3 ? NO_IDENTITIES : IDENTITIES));
    // The configuration file exists only while the CryptoTokenKit runs: read it when it starts.
    List<String> config = new ArrayList<>();
    runner.on(
        List.of("open"),
        command -> {
          Path file = Path.of(command.get(command.indexOf("--config") + 1));
          config.add(Files.readString(file));
          config.add(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
          config.add(
              PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
          return new ProcessRunner.Result(0, "");
        });

    int exit =
        sign(
            "-O",
            "org-id",
            "-p",
            "my-project",
            "-s",
            "release-signing",
            "--identity",
            APP_IDENTITY,
            "--force-overwrite",
            binary.toString());

    assertEquals(0, exit, err.toString());
    List<List<String>> commands = runner.commands();

    // The disk image and the app are checked against SignPath's Developer ID before use.
    List<String> verifyDmg = commands.get(0);
    assertEquals(
        List.of("codesign", "--verify", "--strict", "-R=" + SignPathTokenSession.CODE_REQUIREMENT),
        verifyDmg.subList(0, 4));
    assertTrue(verifyDmg.get(4).endsWith("SignPathCryptoTokenKit.dmg"));
    List<String> attach = commands.get(1);
    assertEquals(
        List.of("hdiutil", "attach", "-readonly", "-nobrowse", "-noautoopen", "-quiet"),
        attach.subList(0, 6));
    Path mountPoint = Path.of(attach.get(attach.indexOf("-mountpoint") + 1));
    Path app = mountPoint.resolve(SignPathTokenSession.APP_NAME);
    assertEquals(
        List.of(
            "codesign",
            "--verify",
            "--strict",
            "-R=" + SignPathTokenSession.CODE_REQUIREMENT,
            app.toString()),
        commands.get(2));

    // The token goes in a configuration file that only the user can read, never on a command
    // line: open does not pass its environment to the app.
    // macOS must use the token extension of this disk image, not one of an earlier session.
    Path extension = app.resolve("Contents/PlugIns/ctk.appex");
    assertEquals(List.of("pluginkit", "-m", "-v", "-p", "com.apple.ctk-tokens"), commands.get(3));
    assertEquals(List.of("pluginkit", "-a", extension.toString()), commands.get(4));
    FakeProcessRunner.Call open = runner.calls().get(5);
    Path configFile = mountPoint.resolveSibling(SignPathTokenSession.CONFIG_FILE);
    assertEquals(
        List.of(
            "open",
            "-n",
            "-g",
            app.toString(),
            "--args",
            "--config",
            configFile.toString(),
            "--organization-id",
            "org-id",
            "--project-slug",
            "my-project",
            "--signing-policy-slug",
            "release-signing"),
        open.command());
    assertEquals(Map.of(), open.environment());
    assertTrue(commands.stream().flatMap(List::stream).noneMatch(arg -> arg.contains(TOKEN)));
    JsonObject json = JsonParser.parseString(config.get(0)).getAsJsonObject();
    assertEquals(TOKEN, json.get("ApiToken").getAsString());
    assertEquals("org-id", json.get("OrganizationId").getAsString());
    assertEquals("my-project", json.get("ProjectSlug").getAsString());
    assertEquals("release-signing", json.get("SigningPolicySlug").getAsString());
    assertEquals("rw-------", config.get(1));
    assertEquals("rwx------", config.get(2));

    // It polls the keychain until the identity shows up.
    assertEquals(3, lookups.get());
    assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), pauses);

    assertEquals(1, runner.commands("codesign", "--force").size());

    // Teardown: stop the app, unmount, delete the download.
    assertEquals(
        List.of("pkill", "-f", SignPathTokenSession.extendedRegexQuote(app.toString())),
        commands.get(commands.size() - 3));
    assertEquals(
        List.of("pluginkit", "-r", extension.toString()), commands.get(commands.size() - 2));
    assertEquals(
        List.of("hdiutil", "detach", mountPoint.toString()), commands.get(commands.size() - 1));
    assertFalse(Files.exists(mountPoint.getParent()), "download directory deleted");
  }

  @Test
  void signPathProvider_withoutIdentity_signsWithThePolicyCertificate() throws Exception {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    policyCertificate = MacOsTestFiles.selfSignedCertificate(tempDir, "Example policy");
    String sha1 = KeychainIdentities.fingerprint(policyCertificate);
    fakeDiskImage();
    runner.on(
        List.of("security", "find-identity"),
        0,
        """
          1) %s "Example policy" (CSSMERR_TP_NOT_TRUSTED)
             1 identities found
        """
            .formatted(sha1));

    int exit =
        sign(
            "-O",
            "org-id",
            "-p",
            "my-project",
            "-s",
            "test-signing",
            "--force-overwrite",
            binary.toString());

    assertEquals(0, exit, err.toString());
    assertEquals(1, certificateLookups.size());
    assertEquals("test-signing", certificateLookups.get(0).signingPolicySlug());
    assertEquals(
        List.of(
            codesign(sha1, "--options", "runtime", binary.toAbsolutePath().normalize().toString())),
        runner.commands("codesign", "--force"));
  }

  @Test
  void signPathProvider_findsTheIdentityInTheCryptoTokenKitToken() throws Exception {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    policyCertificate = MacOsTestFiles.selfSignedCertificate(tempDir, "Example policy");
    String sha1 = KeychainIdentities.fingerprint(policyCertificate);
    fakeDiskImage();
    // As on a GitHub Actions runner: security find-identity does not list the identity of the
    // CryptoTokenKit token, and security export-smartcard prints nothing; system_profiler shows
    // the token's certificate once the token is there.
    runner.on(List.of("security", "find-identity"), 0, NO_IDENTITIES);
    AtomicInteger profiles = new AtomicInteger();
    String pem =
        "-----BEGIN CERTIFICATE-----\n"
            + Base64.getMimeEncoder().encodeToString(policyCertificate)
            + "\n-----END CERTIFICATE-----";
    runner.on(
        List.of("system_profiler", "SPSmartCardsDataType"),
        command ->
            new ProcessRunner.Result(
                0,
                profiles.incrementAndGet() < 2
                    ? "Available SmartCards (keychain):"
                    : "Available SmartCards (keychain):\n  #01: Kind: private RSA 4096-bit\n"
                        + pem));

    int exit =
        sign(
            "-O",
            "org-id",
            "-p",
            "my-project",
            "-s",
            "test-signing",
            "--force-overwrite",
            binary.toString());

    assertEquals(0, exit, err.toString());
    assertEquals(2, profiles.get());
    assertEquals(
        List.of(
            codesign(sha1, "--options", "runtime", binary.toAbsolutePath().normalize().toString())),
        runner.commands("codesign", "--force"));
    assertTrue(err.toString().contains("is not trusted on this machine"), err.toString());
  }

  @Test
  void signPathProvider_withIdentity_doesNotLookUpThePolicyCertificate() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    fakeDiskImage();
    runner.on(List.of("security", "find-identity"), 0, IDENTITIES);

    int exit =
        sign(
            "-O",
            "org-id",
            "-p",
            "my-project",
            "-s",
            "release-signing",
            "--identity",
            APP_IDENTITY,
            "--force-overwrite",
            binary.toString());

    assertEquals(0, exit, err.toString());
    assertTrue(certificateLookups.isEmpty());
  }

  @Test
  void signPathProvider_unknownPolicy_failsBeforeStartingTheCryptoTokenKit() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);

    int exit =
        sign(
            "-O",
            "org-id",
            "-p",
            "my-project",
            "-s",
            "no-such-policy",
            "--force-overwrite",
            binary.toString());

    assertEquals(1, exit);
    assertTrue(err.toString().contains("does not list the signing policy"), err.toString());
    assertTrue(runner.commands().isEmpty());
  }

  @Test
  void signPathProvider_codesignFailure_includesTheCryptoTokenKitLog() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    fakeDiskImage();
    runner.on(List.of("security", "find-identity"), 0, IDENTITIES);
    runner.on(List.of("security", "list-smartcards"), 0, "No smartcards found.");
    runner.on(List.of("codesign", "--force"), 1, "tool: errSecInternalComponent");
    Files.createDirectories(tempDir.resolve("logs"));
    Files.writeString(tempDir.resolve("logs/ctk.log"), "SignHash failed: origin not verified\n");

    int exit =
        sign(
            "-O",
            "org-id",
            "-p",
            "my-project",
            "-s",
            "release-signing",
            "--identity",
            APP_IDENTITY,
            "--force-overwrite",
            binary.toString());

    assertEquals(1, exit);
    String error = err.toString();
    assertTrue(error.contains("errSecInternalComponent"), error);
    assertTrue(error.contains("SignHash failed: origin not verified"), error);
    assertEquals(1, runner.commands("pkill").size());
    assertEquals(1, runner.commands("hdiutil", "detach").size());
  }

  @Test
  void signPathProvider_codesignTimeout_stopsAndIncludesTheCryptoTokenKitLog() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    fakeDiskImage();
    runner.on(List.of("security", "find-identity"), 0, IDENTITIES);
    runner.on(
        List.of("codesign", "--force"), command -> new ProcessRunner.Result(-1, "partial", true));
    Files.createDirectories(tempDir.resolve("logs"));
    Files.writeString(tempDir.resolve("logs/ctk.log"), "SignHash: waiting for build data\n");

    int exit =
        sign(
            "-O",
            "org-id",
            "-p",
            "my-project",
            "-s",
            "release-signing",
            "--identity",
            APP_IDENTITY,
            "--signing-timeout",
            "42",
            "--force-overwrite",
            binary.toString());

    assertEquals(1, exit);
    String error = err.toString();
    assertTrue(error.contains("codesign did not finish within 42s and was stopped"), error);
    assertTrue(error.contains("SignHash: waiting for build data"), error);
    assertEquals(1, runner.commands("pkill").size());
  }

  @Test
  void signPathProvider_refusal_stopsCodesignWithSignPathsReason() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    fakeDiskImage();
    runner.on(List.of("security", "find-identity"), 0, IDENTITIES);
    Path extensionLogs =
        tempDir.resolve("containers/io.signpath.apps.Example/Data/Library/Logs/ctk");
    Files.createDirectories(extensionLogs);
    // As the token extension logs it while codesign waits for an answer that never comes.
    runner.on(
        List.of("codesign", "--force"),
        command -> {
          Files.writeString(
              extensionLogs.resolve("ctk.log"),
              "TokenSession signData\nHTTP Request failed with status code 403"
                  + " {\"title\":\"Forbidden\",\"status\":403,\"detail\":\"The selected"
                  + " signing policy requires that the signing request is submitted via a trusted"
                  + " build system.\"}\n");
          return new ProcessRunner.Result(0, "");
        });

    int exit =
        sign(
            "-O",
            "org-id",
            "-p",
            "my-project",
            "-s",
            "release-signing",
            "--identity",
            APP_IDENTITY,
            "--force-overwrite",
            binary.toString());

    assertEquals(1, exit);
    String error = err.toString();
    assertTrue(
        error.contains(
            "SignPath refused to sign the hashes of codesign: HTTP 403: The selected signing policy"
                + " requires that the signing request is submitted via a trusted build system."),
        error);
    assertEquals(1, runner.commands("pkill").size());
  }

  @Test
  void signPathProvider_refusalOfAnEarlierSession_isIgnored() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    fakeDiskImage();
    runner.on(List.of("security", "find-identity"), 0, IDENTITIES);
    Path extensionLogs =
        tempDir.resolve("containers/io.signpath.apps.Example/Data/Library/Logs/ctk");
    Files.createDirectories(extensionLogs);
    Files.writeString(
        extensionLogs.resolve("old.log"), "HTTP Request failed with status code 403 {}\n");

    int exit =
        sign(
            "-O",
            "org-id",
            "-p",
            "my-project",
            "-s",
            "release-signing",
            "--identity",
            APP_IDENTITY,
            "--force-overwrite",
            binary.toString());

    assertEquals(0, exit, err.toString());
  }

  @Test
  void signPathProvider_customApiUrlIsPassedToCryptoTokenKit() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    fakeDiskImage();
    runner.on(List.of("security", "find-identity"), 0, IDENTITIES);

    int exit =
        sign(
            "-O",
            "org-id",
            "-p",
            "my-project",
            "-s",
            "release-signing",
            "--base-url",
            "https://signpath.example.org/Api",
            "--identity",
            APP_IDENTITY,
            "--force-overwrite",
            binary.toString());

    assertEquals(0, exit, err.toString());
    List<String> open = runner.commands("open").get(0);
    assertEquals(
        List.of("--api-url", "https://signpath.example.org/Api"),
        open.subList(open.size() - 2, open.size()));
  }

  @Test
  void signPathProvider_identityNeverAppears_failsWithDiagnosticsAndTearsDown() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    fakeDiskImage();
    runner.on(List.of("security", "find-identity"), 0, NO_IDENTITIES);
    runner.on(List.of("security", "list-smartcards"), 0, "No smartcards found.");
    Files.createDirectories(tempDir.resolve("logs"));
    Files.writeString(tempDir.resolve("logs/ctk.log"), "auth failed for token " + TOKEN + "\n");
    // The token extension logs in its sandbox container.
    Path extensionLogs =
        tempDir.resolve("containers/io.signpath.apps.Example.ctk/Data/Library/Logs/ctk");
    Files.createDirectories(extensionLogs);
    Files.writeString(extensionLogs.resolve("ctk.log"), "certificate sync failed\n");
    Files.createDirectories(tempDir.resolve("containers/com.example.Other/Data/Library/Logs"));
    Files.writeString(
        tempDir.resolve("containers/com.example.Other/Data/Library/Logs/other.log"), "unrelated\n");
    runner.on(List.of("pluginkit"), 0, "+    io.signpath.apps.Example.ctk(2.0)");

    int exit =
        sign(
            "-O",
            "org-id",
            "-p",
            "my-project",
            "-s",
            "release-signing",
            "--identity",
            APP_IDENTITY,
            "--identity-timeout",
            "3",
            "--force-overwrite",
            binary.toString());

    assertEquals(1, exit);
    String error = err.toString();
    assertTrue(error.contains("not found in the keychain after 3s"), error);
    assertTrue(error.contains("No smartcards found."), error);
    assertTrue(error.contains("auth failed for token ***"), error);
    assertTrue(error.contains("certificate sync failed"), error);
    assertFalse(error.contains("unrelated"), error);
    assertTrue(error.contains("security find-identity:"), error);
    assertTrue(error.contains("io.signpath.apps.Example.ctk(2.0)"), error);
    assertFalse(error.contains(TOKEN), "token masked in the log excerpt");
    assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), pauses);
    assertTrue(runner.commands("codesign", "--force").isEmpty());
    assertEquals(1, runner.commands("pkill").size());
    assertEquals(1, runner.commands("hdiutil", "detach").size());
  }

  @Test
  void signPathProvider_checksumMismatch_failsBeforeMounting() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);

    int exit =
        sign(
            "-O",
            "org-id",
            "-p",
            "my-project",
            "-s",
            "release-signing",
            "--ctk-sha256",
            "00".repeat(32),
            "--identity",
            APP_IDENTITY,
            "--force-overwrite",
            binary.toString());

    assertEquals(1, exit);
    assertTrue(err.toString().contains("Checksum mismatch"), err.toString());
    assertTrue(runner.commands("hdiutil").isEmpty());
    assertTrue(runner.commands("open").isEmpty());
  }

  @Test
  void signPathProvider_diskImageNotSignedBySignPath_failsBeforeMounting() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    runner.on(
        List.of("codesign", "--verify", "--strict", "-R=" + SignPathTokenSession.CODE_REQUIREMENT),
        3,
        "test-requirement: code failed to satisfy specified code requirement(s)");

    int exit =
        sign(
            "-O",
            "org-id",
            "-p",
            "my-project",
            "-s",
            "release-signing",
            "--identity",
            APP_IDENTITY,
            "--force-overwrite",
            binary.toString());

    assertEquals(1, exit);
    assertTrue(
        err.toString().contains("is not signed by SignPath GmbH (Team ID PDP2N2TC2T)"),
        err.toString());
    assertTrue(runner.commands("hdiutil").isEmpty());
  }

  @Test
  void signPathProvider_requiresCoordinates() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);

    int exit =
        sign(
            "-p", "my-project", "--identity", APP_IDENTITY, "--force-overwrite", binary.toString());

    assertEquals(2, exit);
    assertTrue(
        err.toString().contains("requires --organization-id, --signing-policy"), err.toString());
  }

  // ---- argument validation ----

  @Test
  void requiresOutputDirOrForceOverwrite() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);

    int exit = sign("--token-provider", "keychain", "--identity", APP_IDENTITY, binary.toString());

    assertEquals(2, exit);
    assertTrue(err.toString().contains("--force-overwrite"), err.toString());
  }

  @Test
  void rejectsFilesThatAreNotCode() throws IOException {
    Path text = Files.writeString(tempDir.resolve("notes.txt"), "hello");

    int exit =
        sign(
            "--token-provider",
            "keychain",
            "--identity",
            APP_IDENTITY,
            "--force-overwrite",
            text.toString());

    assertEquals(2, exit);
    assertTrue(err.toString().contains("not a bundle"), err.toString());
  }

  @Test
  void failsOutsideMacOs() throws IOException {
    macOs = false;
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);

    int exit =
        sign(
            "--token-provider",
            "keychain",
            "--identity",
            "-",
            "--force-overwrite",
            binary.toString());

    assertEquals(1, exit);
    assertTrue(err.toString().contains("runs on macOS only"), err.toString());
    assertTrue(runner.commands().isEmpty());
  }

  @Test
  void codesignFailure_isReportedWithItsOutput() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("tool"), MacOsTestFiles.MACH_O_64);
    runner.on(List.of("codesign", "--force"), 1, "tool: errSecInternalComponent");

    int exit =
        sign(
            "--token-provider",
            "keychain",
            "--identity",
            "-",
            "--force-overwrite",
            binary.toString());

    assertEquals(1, exit);
    assertTrue(err.toString().contains("codesign --force failed with exit code 1"), err.toString());
    assertTrue(err.toString().contains("errSecInternalComponent"), err.toString());
  }

  private static void copyTree(Path source, Path target) throws IOException {
    try (Stream<Path> paths = Files.walk(source)) {
      for (Path path : paths.toList()) {
        Path destination = target.resolve(source.relativize(path).toString());
        if (Files.isSymbolicLink(path)) {
          Files.createSymbolicLink(destination, Files.readSymbolicLink(path));
        } else if (Files.isDirectory(path)) {
          Files.createDirectories(destination);
        } else {
          Files.copy(path, destination);
        }
      }
    }
  }
}
