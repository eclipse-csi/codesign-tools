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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MacOsCodeScannerTest {

  @TempDir Path tempDir;

  @Test
  void isMachO_recognizesThinAndUniversalBinaries() throws IOException {
    assertTrue(
        MacOsCodeScanner.isMachO(
            MacOsTestFiles.write(tempDir.resolve("thin"), MacOsTestFiles.MACH_O_64)));
    assertTrue(
        MacOsCodeScanner.isMachO(
            MacOsTestFiles.write(tempDir.resolve("fat"), MacOsTestFiles.MACH_O_FAT)));
    byte[] bigEndian32 = {(byte) 0xFE, (byte) 0xED, (byte) 0xFA, (byte) 0xCE, 0, 0, 0, 7};
    assertTrue(MacOsCodeScanner.isMachO(MacOsTestFiles.write(tempDir.resolve("ppc"), bigEndian32)));
  }

  @Test
  void isMachO_rejectsJavaClassFilesTextAndShortFiles() throws IOException {
    assertFalse(
        MacOsCodeScanner.isMachO(
            MacOsTestFiles.write(tempDir.resolve("A.class"), MacOsTestFiles.JAVA_CLASS)));
    assertFalse(
        MacOsCodeScanner.isMachO(
            Files.writeString(tempDir.resolve("a.txt"), "#!/bin/sh\necho hi\n")));
    assertFalse(
        MacOsCodeScanner.isMachO(
            Files.write(tempDir.resolve("short"), new byte[] {(byte) 0xCF, (byte) 0xFA})));
    assertFalse(MacOsCodeScanner.isMachO(tempDir));
  }

  @Test
  void isMachO_doesNotFollowSymbolicLinks() throws IOException {
    Path binary = MacOsTestFiles.write(tempDir.resolve("bin"), MacOsTestFiles.MACH_O_64);
    Path link = Files.createSymbolicLink(tempDir.resolve("link"), binary);
    assertFalse(MacOsCodeScanner.isMachO(link));
  }

  @Test
  void isBundle_matchesBundleExtensionsOnDirectories() throws IOException {
    assertTrue(MacOsCodeScanner.isBundle(Files.createDirectories(tempDir.resolve("A.app"))));
    assertTrue(MacOsCodeScanner.isBundle(Files.createDirectories(tempDir.resolve("B.Framework"))));
    assertFalse(MacOsCodeScanner.isBundle(Files.createDirectories(tempDir.resolve("plain"))));
    assertFalse(MacOsCodeScanner.isBundle(Files.writeString(tempDir.resolve("C.app"), "file")));
  }

  @Test
  void signingOrder_isInsideOutWithTopLevelBundleLast() throws IOException {
    Path app = MacOsTestFiles.sampleApp(tempDir).toRealPath();
    Path contents = app.resolve("Contents");

    List<Path> order = MacOsCodeScanner.signingOrder(app);

    assertEquals(
        List.of(
            contents.resolve("Frameworks/Lib.framework/Versions/A/Lib"),
            contents.resolve("Frameworks/Lib.framework"),
            contents.resolve("Frameworks/libhelper.dylib"),
            contents.resolve("MacOS/Sample"),
            app),
        order);
  }

  @Test
  void signingOrder_ofBundleWithoutNestedCode_isTheBundle() throws IOException {
    Path bundle = Files.createDirectories(tempDir.resolve("Empty.bundle")).toRealPath();
    assertEquals(List.of(bundle), MacOsCodeScanner.signingOrder(bundle));
  }

  // ---- KeychainIdentities ----

  private static final String FIND_IDENTITY_OUTPUT =
      """
        1) 0123456789ABCDEF0123456789ABCDEF01234567 "Developer ID Application: Example AISBL (TEAM123456)"
        2) 89abcdef0123456789abcdef0123456789abcdef "Developer ID Installer: Example AISBL (TEAM123456)"
           2 valid identities found
      """;

  @Test
  void parse_readsSecurityFindIdentityOutput() {
    List<KeychainIdentities.Identity> identities = KeychainIdentities.parse(FIND_IDENTITY_OUTPUT);
    assertEquals(
        List.of(
            new KeychainIdentities.Identity(
                "0123456789ABCDEF0123456789ABCDEF01234567",
                "Developer ID Application: Example AISBL (TEAM123456)"),
            new KeychainIdentities.Identity(
                "89ABCDEF0123456789ABCDEF0123456789ABCDEF",
                "Developer ID Installer: Example AISBL (TEAM123456)")),
        identities);
    assertTrue(KeychainIdentities.parse("     0 valid identities found\n").isEmpty());
  }

  /** {@code security find-identity} without {@code -v}, with valid and invalid identities. */
  private static final String FIND_IDENTITY_ALL_OUTPUT =
      """
      Policy: X.509 Basic
        Matching identities
        1) 1111111111111111111111111111111111111111 "Example test signing" (CSSMERR_TP_NOT_TRUSTED)
        2) 2222222222222222222222222222222222222222 "Developer ID Application: Example AISBL (TEAM123456)" (CSSMERR_TP_CERT_EXPIRED)
        3) 0123456789ABCDEF0123456789ABCDEF01234567 "Developer ID Application: Example AISBL (TEAM123456)"
           3 identities found

        Valid identities only
        1) 0123456789ABCDEF0123456789ABCDEF01234567 "Developer ID Application: Example AISBL (TEAM123456)"
           1 valid identities found
      """;

  @Test
  void parse_readsProblemsAndListsEachIdentityOnce() {
    List<KeychainIdentities.Identity> identities =
        KeychainIdentities.parse(FIND_IDENTITY_ALL_OUTPUT);
    assertEquals(
        List.of(
            new KeychainIdentities.Identity(
                "1111111111111111111111111111111111111111",
                "Example test signing",
                "CSSMERR_TP_NOT_TRUSTED"),
            new KeychainIdentities.Identity(
                "2222222222222222222222222222222222222222",
                "Developer ID Application: Example AISBL (TEAM123456)",
                "CSSMERR_TP_CERT_EXPIRED"),
            new KeychainIdentities.Identity(
                "0123456789ABCDEF0123456789ABCDEF01234567",
                "Developer ID Application: Example AISBL (TEAM123456)")),
        identities);
    assertTrue(identities.get(0).untrusted());
    assertTrue(identities.get(0).usable());
    assertFalse(identities.get(1).usable());
    assertTrue(identities.get(2).valid());
  }

  @Test
  void select_prefersAnExactNameOverPartialMatches() {
    List<KeychainIdentities.Identity> identities =
        List.of(
            new KeychainIdentities.Identity("1".repeat(40), "Example"),
            new KeychainIdentities.Identity("2".repeat(40), "Example (staging)"));
    assertEquals(List.of(identities.get(0)), KeychainIdentities.select(identities, "Example"));
    assertEquals(identities, KeychainIdentities.select(identities, "Exam"));
    assertEquals(List.of(identities.get(1)), KeychainIdentities.select(identities, "2".repeat(40)));
  }

  @Test
  void await_returnsTheUsableIdentityEachNameSelects() throws Exception {
    FakeProcessRunner runner = new FakeProcessRunner();
    runner.on(List.of("security", "find-identity"), 0, FIND_IDENTITY_ALL_OUTPUT);

    Map<String, KeychainIdentities.Identity> found =
        KeychainIdentities.await(
            runner,
            duration -> {},
            List.of("TEAM123456", "Example test signing"),
            Duration.ZERO,
            false,
            message -> {});

    assertEquals("0123456789ABCDEF0123456789ABCDEF01234567", found.get("TEAM123456").sha1());
    assertEquals("CSSMERR_TP_NOT_TRUSTED", found.get("Example test signing").problem());
  }

  @Test
  void parseCertificates_readsEachPemCertificateOnce(@TempDir Path dir) throws Exception {
    byte[] der = MacOsTestFiles.selfSignedCertificate(dir, "Example token signing");
    String pem = pem(der);
    // As system_profiler SPSmartCardsDataType lists a token: once under "keychain", once under
    // "token".
    String output =
        """
        Available SmartCards (keychain):
            io.example.ctk:1234:
              #01: Kind: private RSA 2048-bit, Usage: Sign
        %s
        Available SmartCards (token):
            io.example.ctk:1234:
        %s
        """
            .formatted(pem, pem);

    List<KeychainIdentities.Identity> identities = KeychainIdentities.parseCertificates(output);

    assertEquals(1, identities.size());
    KeychainIdentities.Identity identity = identities.get(0);
    assertEquals(KeychainIdentities.fingerprint(der), identity.sha1());
    assertEquals("Example token signing", identity.name());
    assertTrue(identity.untrusted(), "a self-signed certificate is taken as untrusted");
  }

  @Test
  void parseCertificates_ignoresTextWithoutCertificates() {
    assertTrue(KeychainIdentities.parseCertificates("No smartcards found.").isEmpty());
    assertTrue(
        KeychainIdentities.parseCertificates(
                "-----BEGIN CERTIFICATE-----\nbm90IGEgY2VydGlmaWNhdGU=\n-----END CERTIFICATE-----")
            .isEmpty());
    assertTrue(KeychainIdentities.parseCertificates(null).isEmpty());
  }

  @Test
  void listTokens_fallsBackToSystemProfiler(@TempDir Path dir) throws Exception {
    byte[] der = MacOsTestFiles.selfSignedCertificate(dir, "Example token signing");
    FakeProcessRunner runner = new FakeProcessRunner();
    runner.on(List.of("security", "export-smartcard"), 0, "");
    runner.on(List.of("system_profiler", "SPSmartCardsDataType"), 0, pem(der));

    List<KeychainIdentities.Identity> identities = KeychainIdentities.listTokens(runner);

    assertEquals(
        List.of(KeychainIdentities.fingerprint(der)),
        identities.stream().map(KeychainIdentities.Identity::sha1).toList());
  }

  @Test
  void await_findsAnIdentityOnlyACryptoTokenKitTokenHolds(@TempDir Path dir) throws Exception {
    byte[] der = MacOsTestFiles.selfSignedCertificate(dir, "Example token signing");
    String sha1 = KeychainIdentities.fingerprint(der);
    FakeProcessRunner runner = new FakeProcessRunner();
    // security find-identity does not list the identities of CryptoTokenKit tokens.
    runner.on(List.of("security", "find-identity"), 0, "     0 identities found");
    runner.on(List.of("security", "export-smartcard"), 0, pem(der));

    Map<String, KeychainIdentities.Identity> found =
        KeychainIdentities.await(
            runner, duration -> {}, List.of(sha1), Duration.ZERO, true, message -> {});

    assertEquals("Example token signing", found.get(sha1).name());
    assertThrows(
        ToolException.class,
        () ->
            KeychainIdentities.await(
                runner, duration -> {}, List.of(sha1), Duration.ZERO, false, message -> {}));
  }

  private static String pem(byte[] der) {
    return "-----BEGIN CERTIFICATE-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
        + "\n-----END CERTIFICATE-----";
  }

  @Test
  void matches_bySha1OrNameSubstring() {
    KeychainIdentities.Identity identity = KeychainIdentities.parse(FIND_IDENTITY_OUTPUT).get(0);
    assertTrue(KeychainIdentities.matches(identity, "0123456789abcdef0123456789abcdef01234567"));
    assertTrue(KeychainIdentities.matches(identity, "Developer ID Application: Example"));
    assertTrue(KeychainIdentities.matches(identity, "TEAM123456"));
    assertFalse(KeychainIdentities.matches(identity, "Developer ID Installer"));
    assertFalse(KeychainIdentities.matches(identity, "1111111111111111111111111111111111111111"));
  }
}
