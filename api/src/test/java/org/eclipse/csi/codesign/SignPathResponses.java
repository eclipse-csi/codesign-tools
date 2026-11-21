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

import mockwebserver3.MockResponse;

/**
 * Realistic SignPath API responses for {@code MockWebServer}, modelled on sanitized captures from a
 * real SignPath organization (all identifiers are fake).
 *
 * <p>Notable real-world traits these fixtures preserve:
 *
 * <ul>
 *   <li>submit answers {@code 201} with both a {@code Location} header and a JSON body;
 *   <li>the status document carries many more fields than {@link SigningRequestStatus} maps, and
 *       {@code signedArtifactLink} is present in every state, not only when completed;
 *   <li>errors are {@code application/problem+json} documents (RFC 9457) with a {@code traceId},
 *       and validation failures add an {@code errors} map keyed by form field.
 * </ul>
 */
final class SignPathResponses {

  static final String REQUEST_ID = "5f0c3b8e-2a71-4c1d-9e46-0b7d2f9a1c35";

  private SignPathResponses() {}

  /** URL of a signing request, as SignPath returns it in the submit {@code Location} header. */
  static String signingRequestUrl(String apiBaseUrl, String organizationId, String requestId) {
    return apiBaseUrl + "/v1/" + organizationId + "/SigningRequests/" + requestId;
  }

  /** {@code 201 Created} answer to {@code POST /v1/{org}/SigningRequests}. */
  static MockResponse submitted(String apiBaseUrl, String organizationId, String requestId) {
    return new MockResponse.Builder()
        .code(201)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setHeader("Location", signingRequestUrl(apiBaseUrl, organizationId, requestId))
        .body("{\"signingRequestId\":\"" + requestId + "\"}")
        .build();
  }

  /** {@code 200 OK} answer to {@code GET /v1/{org}/SigningRequests/{id}}. */
  static MockResponse status(
      String apiBaseUrl,
      String organizationId,
      String requestId,
      String status,
      String workflowStatus,
      boolean isFinalStatus) {
    return new MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .body(
            statusJson(
                apiBaseUrl, organizationId, requestId, status, workflowStatus, isFinalStatus))
        .build();
  }

  /** Body of {@code GET /v1/{org}/SigningRequests/{id}}. */
  static String statusJson(
      String apiBaseUrl,
      String organizationId,
      String requestId,
      String status,
      String workflowStatus,
      boolean isFinalStatus) {
    String requestUrl = signingRequestUrl(apiBaseUrl, organizationId, requestId);
    String webLink =
        apiBaseUrl.replaceFirst("/Api$", "/Web")
            + "/"
            + organizationId
            + "/SigningRequests/"
            + requestId;
    String malwareScanningStatus =
        switch (status) {
          case "Completed", "Failed" -> "Completed";
          case "Denied" -> "NotApplicableDueToDenial";
          case "Canceled" -> "NotApplicableDueToCancellation";
          default -> "Pending";
        };
    return """
    {
      "signingRequestId": "%1$s",
      "status": "%2$s",
      "workflowStatus": "%3$s",
      "malwareScanningStatus": "%4$s",
      "isFinalStatus": %5$s,
      "description": "Build 42",
      "projectId": "0d9e8a77-31b5-4f0e-a3c2-6f1b2e4d5a60",
      "projectSlug": "my-project",
      "projectName": "My Project",
      "artifactConfigurationId": "7c2f4e19-5b8a-4d3c-9f01-2a6e8d3b4c71",
      "artifactConfigurationSlug": "initial",
      "artifactConfigurationName": "Initial version",
      "signingPolicyId": "b4e1d0a2-6c3f-4a58-8e97-1f2d3c4b5a82",
      "signingPolicySlug": "test-signing",
      "signingPolicyName": "Test signing",
      "unsignedArtifactLink": "%6$s/UnsignedArtifact",
      "signedArtifactLink": "%6$s/SignedArtifact",
      "artifactDescriptionXmlLink": "%6$s/ArtifactDescriptionXml",
      "parameters": {"version": "1.2.3"},
      "webLink": "%7$s"
    }
    """
        .formatted(
            requestId,
            status,
            workflowStatus,
            malwareScanningStatus,
            isFinalStatus,
            requestUrl,
            webLink);
  }

  /** A {@code problem+json} error response. */
  static MockResponse problem(int code, String json) {
    return new MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/problem+json")
        .body(json)
        .build();
  }

  /** Real 400 body when the signing policy slug does not exist in the project. */
  static final String UNKNOWN_SIGNING_POLICY =
      """
      {"errors":{"SigningPolicySlug":["There is no signing policy with slug 'no-such-policy' \
      in the project with slug 'my-project'."]},\
      "type":"https://tools.ietf.org/html/rfc9110#section-15.5.1",\
      "title":"One or more validation errors occurred.","status":400,\
      "traceId":"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"}\
      """;

  /** Real 401 body when the token cannot be parsed. */
  static final String MALFORMED_TOKEN =
      """
      {"type":"https://tools.ietf.org/html/rfc9110#section-15.5.2","title":"Unauthorized",\
      "status":401,"detail":"Malformed 'Authorization' header (non-parseable token at position 1/1).",\
      "traceId":"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"}\
      """;

  /** Real 404 body for an unknown signing request. */
  static final String NOT_FOUND =
      """
      {"type":"https://tools.ietf.org/html/rfc9110#section-15.5.5","title":"Not Found",\
      "status":404,"detail":"No matching entity was found",\
      "traceId":"00-5c2f1e0d9b8a7f6e5d4c3b2a19087f6e-1a2b3c4d5e6f7a8b-01"}\
      """;

  /** Real 400 body when downloading the signed artifact of a request that is not completed. */
  static final String ARTIFACT_NOT_AVAILABLE =
      """
      {"type":"https://tools.ietf.org/html/rfc9110#section-15.5.1","title":"Bad Request",\
      "status":400,"detail":"The signing request's artifact is not available.",\
      "traceId":"00-9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b-0123456789abcdef-01"}\
      """;
}
