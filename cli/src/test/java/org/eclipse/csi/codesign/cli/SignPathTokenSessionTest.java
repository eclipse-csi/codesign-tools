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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

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
}
