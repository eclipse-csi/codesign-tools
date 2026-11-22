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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SigningWorkflowTest {

  private MockWebServer server;
  private CodesignClient client;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();

    OkHttpClient httpClient = new OkHttpClient();
    client =
        new CodesignClient(httpClient, server.url("/Api").toString(), "test-org-id", "test-token");
  }

  @AfterEach
  void tearDown() throws Exception {
    client.close();
    server.close();
  }

  @Test
  void preUploadSha256IsLoggedBeforeSubmit() throws Exception {
    enqueueSubmit(42);
    server.enqueue(statusResponse("Completed", "Completed", true));

    Path artifact = tempDir.resolve("artifact.jar");
    Files.writeString(artifact, "test-artifact-content");

    List<String> logLines = new ArrayList<>();
    SigningWorkflow workflow = new SigningWorkflow(client, Duration.ofMillis(10), logLines::add);

    workflow.submitAndWait("proj", "policy", null, null, null, artifact);

    // Verify that a SHA-256 line was logged before the "Signing request submitted" line
    int sha256Index = -1;
    int submittedIndex = -1;
    for (int i = 0; i < logLines.size(); i++) {
      String line = logLines.get(i);
      if (line.startsWith("Artifact SHA-256 (pre-upload): ")) {
        sha256Index = i;
      }
      if (line.startsWith("Signing request submitted: ")) {
        submittedIndex = i;
      }
    }

    assertTrue(sha256Index >= 0, "Expected SHA-256 log line");
    assertTrue(submittedIndex >= 0, "Expected 'Signing request submitted' log line");
    assertTrue(sha256Index < submittedIndex, "SHA-256 must be logged before submit confirmation");

    String sha256Line = logLines.get(sha256Index);
    String hash = sha256Line.substring("Artifact SHA-256 (pre-upload): ".length());
    // sha256("test-artifact-content")
    assertEquals("4b944cb918f80d35ac2a8aff5b7ccea356f018a697b73d7ffbd2cbe66adbc547", hash);
  }

  @Test
  void completionTimeoutAppliesPerRequest() throws Exception {
    Path first = tempDir.resolve("first.jar");
    Path second = tempDir.resolve("second.jar");
    Files.writeString(first, "first");
    Files.writeString(second, "second");

    SigningWorkflow workflow =
        new SigningWorkflow(client, Duration.ofMillis(10), Duration.ofMillis(300), null);

    enqueueSubmit(1);
    server.enqueue(statusResponse("Completed", "Completed", true));
    workflow.submitAndWait("proj", "policy", null, null, null, first);

    // The second submission outlasts the timeout: a deadline shared with the first request
    // would already be expired when the second one starts polling.
    server.enqueue(
        SignPathResponses.submitted(apiBaseUrl(), "test-org-id", "2")
            .newBuilder()
            .headersDelay(400, TimeUnit.MILLISECONDS)
            .build());
    server.enqueue(statusResponse("InProgress", "QueuedForMalwareScanning", false));
    server.enqueue(statusResponse("Completed", "Completed", true));
    SigningRequestStatus status =
        workflow.submitAndWait("proj", "policy", null, null, null, second);

    assertEquals("Completed", status.status());
  }

  @Test
  void completionTimeoutStillEnforcedWithinRequest() throws Exception {
    String statusUrl = server.url("/Api/v1/test-org-id/SigningRequests/1").toString();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            if ("POST".equals(request.getMethod())) {
              return new MockResponse.Builder().code(201).setHeader("Location", statusUrl).build();
            }
            return statusResponse("WaitingForApproval", "WaitingForApproval", false);
          }
        });

    Path artifact = tempDir.resolve("artifact.jar");
    Files.writeString(artifact, "test-artifact-content");

    SigningWorkflow workflow =
        new SigningWorkflow(client, Duration.ofMillis(20), Duration.ofMillis(50), null);

    CodesignException e =
        assertThrows(
            CodesignException.class,
            () -> workflow.submitAndWait("proj", "policy", null, null, null, artifact));
    assertTrue(
        e.getMessage().contains("Timeout waiting for signing request to complete"),
        "Unexpected message: " + e.getMessage());
    assertTrue(
        e.getMessage().contains("Last status: WaitingForApproval/WaitingForApproval"),
        "Unexpected message: " + e.getMessage());
    assertTrue(
        e.getMessage()
            .endsWith(
                ". The signing request is still open in SignPath: "
                    + server.url("/Web").toString()
                    + "/test-org-id/SigningRequests/"
                    + SignPathResponses.REQUEST_ID),
        "Unexpected message: " + e.getMessage());
  }

  @Test
  void pollsThroughRealWorkflowStatesUntilCompleted() throws Exception {
    enqueueSubmit(7);
    server.enqueue(statusResponse("InProgress", "QueuedForMalwareScanning", false));
    server.enqueue(statusResponse("InProgress", "Processing", false));
    server.enqueue(statusResponse("Completed", "Completed", true));

    Path artifact = tempDir.resolve("artifact.jar");
    Files.writeString(artifact, "content");
    List<String> logLines = new ArrayList<>();
    SigningWorkflow workflow = new SigningWorkflow(client, Duration.ofMillis(10), logLines::add);

    SigningRequestStatus status =
        workflow.submitAndWait("proj", "policy", null, null, null, artifact);

    assertTrue(status.isCompleted());
    assertEquals(
        List.of(
            "Signing status: InProgress (workflow: QueuedForMalwareScanning)",
            "Signing status: InProgress (workflow: Processing)",
            "Signing status: Completed (workflow: Completed)"),
        logLines.stream().filter(l -> l.startsWith("Signing status: ")).toList());
    assertEquals(4, server.getRequestCount(), "1 submit + 3 status polls");
  }

  @Test
  void returnsDeniedStatusWithoutError() throws Exception {
    enqueueSubmit(8);
    server.enqueue(statusResponse("WaitingForApproval", "WaitingForApproval", false));
    server.enqueue(statusResponse("Denied", "Denied", true));

    Path artifact = tempDir.resolve("artifact.jar");
    Files.writeString(artifact, "content");
    SigningWorkflow workflow = new SigningWorkflow(client, Duration.ofMillis(10), null);

    SigningRequestStatus status =
        workflow.submitAndWait("proj", "policy", null, null, null, artifact);

    assertTrue(status.isDenied());
    assertFalse(status.isCompleted());
    assertNotNull(status.webLink());
  }

  private String apiBaseUrl() {
    return server.url("/Api").toString();
  }

  private void enqueueSubmit(int requestId) {
    server.enqueue(
        SignPathResponses.submitted(apiBaseUrl(), "test-org-id", String.valueOf(requestId)));
  }

  private MockResponse statusResponse(String status, String workflowStatus, boolean isFinalStatus) {
    return SignPathResponses.status(
        apiBaseUrl(),
        "test-org-id",
        SignPathResponses.REQUEST_ID,
        status,
        workflowStatus,
        isFinalStatus);
  }
}
