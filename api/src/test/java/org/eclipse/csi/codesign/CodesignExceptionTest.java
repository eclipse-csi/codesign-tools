/*
 * Copyright (c) 2026 Eclipse Foundation AISBL and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.csi.codesign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CodesignExceptionTest {

  @Test
  void validationProblemListsFieldErrors() {
    CodesignException ex = new CodesignException(400, SignPathResponses.UNKNOWN_SIGNING_POLICY);

    assertEquals(
        "SignPath API error (HTTP 400): One or more validation errors occurred."
            + " SigningPolicySlug: There is no signing policy with slug 'no-such-policy'"
            + " in the project with slug 'my-project'."
            + " (trace ID: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01)",
        ex.getMessage());
    assertEquals(400, ex.httpStatus());
    assertEquals(SignPathResponses.UNKNOWN_SIGNING_POLICY, ex.responseBody());
  }

  @Test
  void problemWithDetailJoinsTitleAndDetail() {
    CodesignException ex = new CodesignException(401, SignPathResponses.MALFORMED_TOKEN);

    assertEquals(
        "SignPath API error (HTTP 401): Unauthorized."
            + " Malformed 'Authorization' header (non-parseable token at position 1/1)."
            + " (trace ID: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01)",
        ex.getMessage());
  }

  @Test
  void problemWithOnlyTitle() {
    CodesignException ex =
        new CodesignException(
            404,
            "{\"type\":\"https://tools.ietf.org/html/rfc9110#section-15.5.5\","
                + "\"title\":\"Not Found\",\"status\":404}");

    assertEquals("SignPath API error (HTTP 404): Not Found", ex.getMessage());
  }

  @Test
  void detailIdenticalToTitleIsNotRepeated() {
    CodesignException ex =
        new CodesignException(403, "{\"title\":\"Forbidden\",\"detail\":\"Forbidden\"}");

    assertEquals("SignPath API error (HTTP 403): Forbidden", ex.getMessage());
  }

  @Test
  void multipleFieldErrorsAreAllReported() {
    CodesignException ex =
        new CodesignException(
            400,
            """
            {"title":"One or more validation errors occurred.","status":400,
             "errors":{"Parameters":["The parameters 'version' are required."],
                       "Artifact":["first","second"],
                       "":["no field"]}}\
            """);

    assertEquals(
        "SignPath API error (HTTP 400): One or more validation errors occurred."
            + " Parameters: The parameters 'version' are required."
            + " Artifact: first second. no field",
        ex.getMessage());
  }

  @Test
  void nonJsonBodyIsUsedVerbatim() {
    CodesignException ex = new CodesignException(502, "<html>Bad Gateway</html>");

    assertEquals("SignPath API error (HTTP 502): <html>Bad Gateway</html>", ex.getMessage());
  }

  @Test
  void jsonWithoutProblemFieldsIsUsedVerbatim() {
    CodesignException ex = new CodesignException(500, "{\"message\":\"boom\"}");

    assertEquals("SignPath API error (HTTP 500): {\"message\":\"boom\"}", ex.getMessage());
  }

  @Test
  void jsonArrayIsUsedVerbatim() {
    CodesignException ex = new CodesignException(500, "[1,2]");

    assertEquals("SignPath API error (HTTP 500): [1,2]", ex.getMessage());
  }

  @Test
  void emptyBodyOmitsTheSeparator() {
    assertEquals("SignPath API error (HTTP 401)", new CodesignException(401, "").getMessage());
    assertEquals("SignPath API error (HTTP 401)", new CodesignException(401, null).getMessage());
  }

  @Test
  void messageAndCauseConstructorHasNoHttpDetails() {
    IllegalStateException cause = new IllegalStateException("cause");
    CodesignException ex = new CodesignException("Timeout", cause);

    assertEquals("Timeout", ex.getMessage());
    assertEquals(-1, ex.httpStatus());
    assertNull(ex.responseBody());
  }
}
