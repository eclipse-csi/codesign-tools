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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignPathTokenSessionTest {

  private static final SignPathTokenSession.Settings SETTINGS =
      new SignPathTokenSession.Settings(
          "org-id", "my-project", "test-signing", null, "secret-api-token", null, null, null);

  private static final byte[] CERTIFICATE = "not really DER".getBytes(StandardCharsets.UTF_8);

  /** A {@code Cryptoki/MySigningPolicies} answer, as SignPath documents it. */
  private static String policies(String project, String policy) {
    return """
    {"signingPolicies":[{"signingPolicySlug":"%s","projectSlug":"%s","keySizeInBits":2048,\
    "certificateBytes":"%s","keyType":"Rsa"}]}
    """
        .formatted(policy, project, Base64.getEncoder().encodeToString(CERTIFICATE));
  }

  @Test
  void parsePolicyCertificate_returnsTheCertificateOfThePolicy() throws Exception {
    assertArrayEquals(
        CERTIFICATE,
        SignPathTokenSession.parsePolicyCertificate(
            200, policies("my-project", "test-signing"), SETTINGS));
  }

  @Test
  void parsePolicyCertificate_policyNotListed_explainsWhy() {
    ToolException error =
        assertThrows(
            ToolException.class,
            () ->
                SignPathTokenSession.parsePolicyCertificate(
                    200, policies("my-project", "release-signing"), SETTINGS));
    assertTrue(error.getMessage().contains("does not list the signing policy 'test-signing'"));
    assertTrue(error.getMessage().contains("submitter"));
    assertTrue(error.getMessage().contains("origin verification"));
    assertTrue(
        error.getMessage().contains("Policies listed for this token: my-project/release-signing."));
  }

  @Test
  void parsePolicyCertificate_rejectedToken() {
    ToolException error =
        assertThrows(
            ToolException.class,
            () -> SignPathTokenSession.parsePolicyCertificate(401, "", SETTINGS));
    assertTrue(error.getMessage().contains("rejected the API token (HTTP 401)"));
    assertFalse(error.getMessage().contains("secret-api-token"));
  }

  @Test
  void parsePolicyCertificate_serverError_quotesTheAnswer() {
    ToolException error =
        assertThrows(
            ToolException.class,
            () -> SignPathTokenSession.parsePolicyCertificate(503, "maintenance", SETTINGS));
    assertTrue(error.getMessage().contains("HTTP 503"));
    assertTrue(error.getMessage().contains("maintenance"));
  }

  @Test
  void parsePolicyCertificate_unexpectedAnswer() {
    ToolException error =
        assertThrows(
            ToolException.class,
            () -> SignPathTokenSession.parsePolicyCertificate(200, "[1, 2]", SETTINGS));
    assertTrue(error.getMessage().contains("Unexpected answer"));
  }

  @Test
  void staleTokenExtensions_areTheMissingSignPathExtensionsOfOtherApps(@TempDir Path dir)
      throws Exception {
    Path current = dir.resolve("current/mnt/SignPathCryptoTokenKit.app");
    Path currentExtension = current.resolve("Contents/PlugIns/ctk.appex");
    Path installed = dir.resolve("Applications/SignPathCryptoTokenKit.app");
    Path installedExtension = installed.resolve("Contents/PlugIns/ctk.appex");
    Files.createDirectories(currentExtension);
    Files.createDirectories(installedExtension);
    Path unmounted = dir.resolve("old/mnt/SignPathCryptoTokenKit.app/Contents/PlugIns/ctk.appex");
    // As pluginkit -m -v -p com.apple.ctk-tokens prints them.
    String output =
        String.join(
            "\n",
            "     com.apple.CryptoTokenKit.pivtoken(1.0)    726B381D-0F3B    2026-11-09 04:17:54"
                + " +0000   "
                + " /System/Library/Frameworks/CryptoTokenKit.framework/PlugIns/pivtoken.appex",
            "   F io.signpath.apps.CryptoTokenKit(2.0)    85986695-9823    2026-11-22 16:29:46"
                + " +0000    "
                + unmounted,
            "     io.signpath.apps.CryptoTokenKit(2.0)    B6CD4460-D05D    2026-11-22 16:30:18"
                + " +0000    "
                + currentExtension,
            "     io.signpath.apps.CryptoTokenKit(2.0)    72C9A3AA-2044    2026-11-01 10:00:00"
                + " +0000    "
                + installedExtension,
            "   (4 plug-ins)");

    assertEquals(List.of(unmounted), SignPathTokenSession.staleTokenExtensions(output, current));
    assertEquals(List.of(), SignPathTokenSession.staleTokenExtensions(null, current));
  }
}
