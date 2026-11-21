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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodesignClientTest {

  private static final String API_TOKEN = "test-api-token";
  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String BEARER_TOKEN = "Bearer " + API_TOKEN;

  private MockWebServer server;
  private CodesignClient client;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();

    OkHttpClient httpClient = new OkHttpClient();
    client =
        new CodesignClient(httpClient, server.url("/Api").toString(), "test-org-id", API_TOKEN);
  }

  @AfterEach
  void tearDown() throws IOException {
    client.close();
    server.close();
  }

  private String apiBaseUrl() {
    return server.url("/Api").toString().replaceFirst("/$", "");
  }

  @Test
  void submitReturnsSigningRequestWithLocationHeader() throws Exception {
    String statusUrl =
        SignPathResponses.signingRequestUrl(
            apiBaseUrl(), "test-org-id", SignPathResponses.REQUEST_ID);
    server.enqueue(
        SignPathResponses.submitted(apiBaseUrl(), "test-org-id", SignPathResponses.REQUEST_ID));

    Path artifact = tempDir.resolve("test.jar");
    Files.writeString(artifact, "fake-jar-content");

    SigningRequest result =
        client.submit("my-project", "release-signing", null, null, null, artifact);

    assertNotNull(result);
    assertEquals(URI.create(statusUrl), result.statusUrl());

    RecordedRequest recorded = server.takeRequest();
    assertEquals("POST", recorded.getMethod());
    assertTrue(recorded.getTarget().contains("/SigningRequests"));
    assertEquals(BEARER_TOKEN, recorded.getHeaders().get(AUTHORIZATION_HEADER));
    String body = recorded.getBody().utf8();
    assertTrue(body.contains("my-project"));
    assertTrue(body.contains("release-signing"));
  }

  @Test
  void submitWithOptionalParameters() throws Exception {
    server.enqueue(
        SignPathResponses.submitted(apiBaseUrl(), "test-org-id", SignPathResponses.REQUEST_ID));

    Path artifact = tempDir.resolve("test.exe");
    Files.writeString(artifact, "fake-exe-content");

    SigningRequest result =
        client.submit(
            "my-project",
            "release-signing",
            "my-config",
            "Build 42",
            Map.of("version", "1.0"),
            artifact);

    assertNotNull(result);

    RecordedRequest recorded = server.takeRequest();
    String body = recorded.getBody().utf8();
    assertTrue(body.contains("my-config"));
    assertTrue(body.contains("Build 42"));
    assertTrue(body.contains("Parameters[version]"));
    assertTrue(body.contains("1.0"));
  }

  @Test
  void submitThrowsOnNon201() throws Exception {
    server.enqueue(SignPathResponses.problem(400, SignPathResponses.UNKNOWN_SIGNING_POLICY));

    Path artifact = tempDir.resolve("test.jar");
    Files.writeString(artifact, "fake-jar-content");

    CodesignException ex =
        assertThrows(
            CodesignException.class,
            () -> client.submit("my-project", "no-such-policy", null, null, null, artifact));
    assertEquals(400, ex.httpStatus());
    assertEquals(SignPathResponses.UNKNOWN_SIGNING_POLICY, ex.responseBody());
    assertEquals(
        "SignPath API error (HTTP 400): One or more validation errors occurred."
            + " SigningPolicySlug: There is no signing policy with slug 'no-such-policy'"
            + " in the project with slug 'my-project'."
            + " (trace ID: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01)",
        ex.getMessage());
  }

  @Test
  void submitThrowsOnMissingLocationHeader() throws Exception {
    server.enqueue(new MockResponse.Builder().code(201).build());

    Path artifact = tempDir.resolve("test.jar");
    Files.writeString(artifact, "fake-jar-content");

    CodesignException ex =
        assertThrows(
            CodesignException.class,
            () -> client.submit("proj", "policy", null, null, null, artifact));
    assertTrue(ex.responseBody().contains("Missing Location header"));
  }

  @Test
  void getStatusParsesJsonResponse() throws Exception {
    String base = "https://app.signpath.io/Api";
    String requestUrl =
        SignPathResponses.signingRequestUrl(base, "test-org-id", SignPathResponses.REQUEST_ID);
    server.enqueue(
        SignPathResponses.status(
            base, "test-org-id", SignPathResponses.REQUEST_ID, "Completed", "Completed", true));

    URI statusUrl = server.url("/Api/v1/test-org-id/SigningRequests/123").uri();
    SigningRequest signingRequest = new SigningRequest(statusUrl);

    SigningRequestStatus status = client.getStatus(signingRequest);

    assertTrue(status.isCompleted());
    assertTrue(status.isFinalStatus());
    assertEquals("Completed", status.workflowStatus());
    assertEquals(URI.create(requestUrl + "/SignedArtifact"), status.signedArtifactLink());
    assertEquals(URI.create(requestUrl + "/UnsignedArtifact"), status.unsignedArtifactLink());
    assertEquals(
        URI.create(
            "https://app.signpath.io/Web/test-org-id/SigningRequests/"
                + SignPathResponses.REQUEST_ID),
        status.webLink());

    RecordedRequest recorded = server.takeRequest();
    assertEquals("GET", recorded.getMethod());
    assertEquals(BEARER_TOKEN, recorded.getHeaders().get(AUTHORIZATION_HEADER));
  }

  @Test
  void getStatusParsesFailedResponseThatStillHasASignedArtifactLink() throws Exception {
    // SignPath includes signedArtifactLink in every state; only the status says it is usable
    server.enqueue(
        SignPathResponses.status(
            apiBaseUrl(),
            "test-org-id",
            SignPathResponses.REQUEST_ID,
            "Failed",
            "ProcessingFailed",
            true));

    SigningRequestStatus status =
        client.getStatus(
            new SigningRequest(server.url("/Api/v1/test-org-id/SigningRequests/1").uri()));

    assertTrue(status.isFinalStatus());
    assertTrue(status.isFailed());
    assertFalse(status.isCompleted());
    assertEquals("ProcessingFailed", status.workflowStatus());
    assertNotNull(status.signedArtifactLink());
  }

  @Test
  void getStatusThrowsOnError() throws Exception {
    server.enqueue(SignPathResponses.problem(404, SignPathResponses.NOT_FOUND));

    URI statusUrl = server.url("/test").uri();
    SigningRequest signingRequest = new SigningRequest(statusUrl);

    CodesignException ex =
        assertThrows(CodesignException.class, () -> client.getStatus(signingRequest));
    assertEquals(404, ex.httpStatus());
    assertTrue(
        ex.getMessage().startsWith("SignPath API error (HTTP 404): Not Found. No matching entity"),
        ex.getMessage());
  }

  @Test
  void downloadSignedArtifactWritesToFile() throws Exception {
    String content = "signed-content-bytes";
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/octet-stream")
            .setHeader(
                "Content-Disposition", "attachment; filename=test.jar; filename*=UTF-8''test.jar")
            .body(content)
            .build());

    URI signedLink = server.url("/signed-artifact").uri();
    SigningRequestStatus status =
        new SigningRequestStatus("Completed", "Completed", true, signedLink, null);

    Path output = tempDir.resolve("signed.jar");
    client.downloadSignedArtifact(status, output);

    assertTrue(Files.exists(output));
    assertEquals(content, Files.readString(output));

    RecordedRequest recorded = server.takeRequest();
    assertEquals("GET", recorded.getMethod());
    assertEquals(BEARER_TOKEN, recorded.getHeaders().get(AUTHORIZATION_HEADER));
  }

  @Test
  void downloadThrowsWhenNoLink() {
    SigningRequestStatus status =
        new SigningRequestStatus("Completed", "Completed", true, null, null);

    assertThrows(
        CodesignException.class,
        () -> client.downloadSignedArtifact(status, tempDir.resolve("out.jar")));
  }

  @Test
  void httpBaseUrlIsRejected() {
    CodesignClient.Config config =
        new CodesignClient.Config(
            "http://insecure.example.com/Api",
            "test-org",
            "token",
            java.time.Duration.ofSeconds(5),
            java.time.Duration.ofSeconds(10),
            java.time.Duration.ofSeconds(60),
            java.time.Duration.ofSeconds(5),
            3);
    assertThrows(IllegalArgumentException.class, () -> new CodesignClient(config));
  }

  @Test
  void downloadThrowsOnHttpError() throws Exception {
    server.enqueue(new MockResponse.Builder().code(500).body("Internal Server Error").build());

    URI signedLink = server.url("/signed-artifact").uri();
    SigningRequestStatus status =
        new SigningRequestStatus("Completed", "Completed", true, signedLink, null);

    CodesignException ex =
        assertThrows(
            CodesignException.class,
            () -> client.downloadSignedArtifact(status, tempDir.resolve("out.jar")));
    assertEquals(500, ex.httpStatus());
    assertEquals("SignPath API error (HTTP 500): Internal Server Error", ex.getMessage());
  }

  @Test
  void downloadThrowsWhenArtifactIsNotAvailable() throws Exception {
    // What SignPath answers when downloading the signed artifact of a non-completed request
    server.enqueue(SignPathResponses.problem(400, SignPathResponses.ARTIFACT_NOT_AVAILABLE));

    URI signedLink = server.url("/signed-artifact").uri();
    SigningRequestStatus status =
        new SigningRequestStatus("Failed", "ProcessingFailed", true, signedLink, null);

    Path output = tempDir.resolve("out.jar");
    CodesignException ex =
        assertThrows(CodesignException.class, () -> client.downloadSignedArtifact(status, output));
    assertEquals(400, ex.httpStatus());
    assertTrue(
        ex.getMessage().contains("Bad Request. The signing request's artifact is not available."),
        ex.getMessage());
    assertFalse(Files.exists(output), "Nothing must be written for an error response");
  }
}
