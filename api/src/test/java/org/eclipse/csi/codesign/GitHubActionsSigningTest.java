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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the GitHub connector flow end to end against one {@link MockWebServer} that plays the
 * Actions results service, the blob storage, the SignPath connector and the SignPath API.
 */
class GitHubActionsSigningTest {

  private static final String ORG = "test-org-id";
  private static final String TWIRP = "/twirp/github.actions.results.api.v1.ArtifactService/";
  private static final String CONNECTOR_PATH = "/connector/GitHubActions/GitHubCom";

  private MockWebServer server;
  private CodesignClient client;
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final List<String> logLines = new ArrayList<>();

  /** Answers to the connector submit; the default accepts the request. */
  private MockResponse connectorResponse;

  /** Final status of the signing request. */
  private String finalStatus = "Completed";

  /** Transforms the uploaded zip into the "signed" zip served for download. */
  private java.util.function.UnaryOperator<byte[]> signer = GitHubActionsSigningTest::fakeSign;

  private byte[] uploadedZip;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            requests.add(request);
            return GitHubActionsSigningTest.this.dispatch(request);
          }
        });
    server.start();
    client = new CodesignClient(new OkHttpClient(), server.url("/Api").toString(), ORG, "sp-token");
    connectorResponse =
        json(
            200,
            "{\"signingRequestId\":\"req-1\",\"signingRequestUrl\":\"https://app.example/req-1\","
                + "\"logs\":[{\"level\":\"Information\",\"message\":\"Artifact verified\"}]}");
  }

  @AfterEach
  void tearDown() throws Exception {
    client.close();
    server.close();
  }

  private MockResponse dispatch(RecordedRequest request) {
    String path = request.getUrl().encodedPath();
    if (path.equals(TWIRP + "CreateArtifact")) {
      return json(
          200,
          "{\"ok\":true,\"signed_upload_url\":\"" + server.url("/blob/a.zip?sig=SECRET") + "\"}");
    }
    if (path.equals("/blob/a.zip")) {
      uploadedZip = request.getBody().toByteArray();
      return new MockResponse.Builder().code(201).build();
    }
    if (path.equals(TWIRP + "FinalizeArtifact")) {
      return json(200, "{\"ok\":true,\"artifact_id\":\"4242\"}");
    }
    if (path.equals(CONNECTOR_PATH + "/" + ORG + "/SigningRequests")) {
      return connectorResponse;
    }
    if (path.equals("/Api/v1/" + ORG + "/SigningRequests/req-1")) {
      return SignPathResponses.status(
          server.url("/Api").toString(), ORG, "req-1", finalStatus, finalStatus, true);
    }
    if (path.equals("/Api/v1/" + ORG + "/SigningRequests/req-1/SignedArtifact")) {
      return new MockResponse.Builder()
          .code(200)
          .body(new Buffer().write(signer.apply(uploadedZip)))
          .build();
    }
    return new MockResponse.Builder().code(404).build();
  }

  @Test
  void signsAllFilesInOneRequestAndWritesThemBack() throws Exception {
    Path app = write("lib/app.jar", "app");
    Path tool = write("bin/tool.jar", "tool");
    Path toolOut = tempDir.resolve("out/tool.jar");
    Map<Path, Path> files = new LinkedHashMap<>();
    files.put(app, app);
    files.put(tool, toolOut);

    SigningRequestStatus status = sign(files, Map.of("version", "1.2.3"));

    assertTrue(status.isCompleted());
    assertEquals("signed:app", Files.readString(app));
    assertEquals("signed:tool", Files.readString(toolOut));
    assertEquals("tool", Files.readString(tool), "input untouched when the output differs");

    // the zip holds both files at its root, in order
    assertEquals(List.of("app.jar", "tool.jar"), new ArrayList<>(unzip(uploadedZip).keySet()));

    RecordedRequest create = request(TWIRP + "CreateArtifact");
    assertEquals("Bearer " + runtimeToken(), create.getHeaders().get("Authorization"));
    JsonObject createBody = body(create);
    assertEquals("run-backend", createBody.get("workflow_run_backend_id").getAsString());
    assertEquals("job-backend", createBody.get("workflow_job_run_backend_id").getAsString());
    assertTrue(createBody.get("name").getAsString().startsWith("codesign-unsigned-"));
    assertEquals(7, createBody.get("version").getAsInt());
    assertEquals("application/zip", createBody.get("mime_type").getAsString());
    assertTrue(createBody.has("expires_at"));

    RecordedRequest blob = request("/blob/a.zip");
    assertEquals("PUT", blob.getMethod());
    assertEquals("BlockBlob", blob.getHeaders().get("x-ms-blob-type"));

    JsonObject finalizeBody = body(request(TWIRP + "FinalizeArtifact"));
    assertEquals(createBody.get("name"), finalizeBody.get("name"));
    assertEquals(Integer.toString(uploadedZip.length), finalizeBody.get("size").getAsString());
    assertTrue(finalizeBody.get("hash").getAsString().startsWith("sha256:"));

    RecordedRequest submit = request(CONNECTOR_PATH + "/" + ORG + "/SigningRequests");
    assertEquals("1.0", submit.getUrl().queryParameter("api-version"));
    assertEquals("Bearer sp-token", submit.getHeaders().get("Authorization"));
    JsonObject payload = body(submit);
    assertEquals("4242", payload.get("artifactId").getAsString());
    assertEquals("12345", payload.get("gitHubWorkflowRunId").getAsString());
    assertEquals("eclipse-csi/codesign-tools", payload.get("gitHubRepository").getAsString());
    assertEquals("gh-token", payload.get("gitHubToken").getAsString());
    assertEquals("proj", payload.get("signPathProjectSlug").getAsString());
    assertEquals("policy", payload.get("signPathSigningPolicySlug").getAsString());
    assertEquals("jar-in-zip", payload.get("signPathArtifactConfigurationSlug").getAsString());
    assertEquals(
        JsonParser.parseString("[{\"name\":\"version\",\"value\":\"1.2.3\"}]"),
        payload.get("parameters"));

    try (var leftovers = Files.list(app.getParent())) {
      assertEquals(List.of(app), leftovers.toList(), "work directory next to the output removed");
    }

    assertTrue(logLines.contains("[SignPath connector] Information: Artifact verified"));
    assertTrue(logLines.contains("Signing request: https://app.example/req-1"));
    assertFalse(String.join("\n", logLines).contains("SECRET"), "upload URL must not be logged");
  }

  @Test
  void singleFileIsUploadedAsIsUnderItsOwnName() throws Exception {
    Path exe = write("bin/tool.exe", "binary");
    Path out = tempDir.resolve("signed/tool.exe");

    SigningRequestStatus status = sign(Map.of(exe, out), null);

    assertTrue(status.isCompleted());
    assertEquals("signed:binary", Files.readString(out));
    assertEquals("binary", new String(uploadedZip, StandardCharsets.UTF_8), "uploaded unzipped");
    JsonObject createBody = body(request(TWIRP + "CreateArtifact"));
    assertEquals("tool.exe", createBody.get("name").getAsString());
    assertEquals("application/octet-stream", createBody.get("mime_type").getAsString());
    assertEquals(
        "application/octet-stream", request("/blob/a.zip").getHeaders().get("Content-Type"));
    assertEquals("tool.exe", body(request(TWIRP + "FinalizeArtifact")).get("name").getAsString());
  }

  @Test
  void existingArtifactNameIsExplained() throws Exception {
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            return json(409, "{\"code\":\"already_exists\",\"msg\":\"artifact exists\"}");
          }
        });
    Path exe = write("tool.exe", "binary");

    CodesignException e = assertThrows(CodesignException.class, () -> sign(Map.of(exe, exe), null));

    assertTrue(
        e.getMessage().contains("This workflow run already has an artifact named 'tool.exe'"),
        e.getMessage());
  }

  @Test
  void invalidArtifactNameIsRejectedBeforeUploading() throws Exception {
    Path file = write("what?.jar", "x");

    CodesignException e =
        assertThrows(CodesignException.class, () -> sign(Map.of(file, file), null));

    assertTrue(e.getMessage().contains("is not a valid GitHub artifact name"), e.getMessage());
    assertTrue(requests.isEmpty());
  }

  @Test
  void policyViolationsAreReportedWithHowToFix() throws Exception {
    connectorResponse =
        json(
            400,
            "{\"validationResult\":{\"errors\":[{\"error\":\"Branch 'feature' is not allowed\","
                + "\"howToFix\":\"Build from main\"}]},"
                + "\"logs\":[{\"level\":\"Error\",\"message\":\"Origin verification failed\"}]}");
    Path app = write("app.jar", "app");

    CodesignException e = assertThrows(CodesignException.class, () -> sign(Map.of(app, app), null));

    assertEquals(
        "The SignPath connector rejected the signing request: Policy violation: Branch 'feature'"
            + " is not allowed. How to fix: Build from main.",
        e.getMessage());
    assertTrue(logLines.contains("[SignPath connector] Error: Origin verification failed"));
    assertEquals("app", Files.readString(app));
  }

  @Test
  void connectorErrorIsReported() throws Exception {
    connectorResponse = json(403, "{\"error\":\"The API token is not allowed to submit\"}");
    Path app = write("app.jar", "app");

    CodesignException e = assertThrows(CodesignException.class, () -> sign(Map.of(app, app), null));

    assertEquals(
        "The SignPath connector rejected the signing request: The API token is not allowed to"
            + " submit.",
        e.getMessage());
  }

  @Test
  void connectorServerErrorKeepsStatus() throws Exception {
    connectorResponse = new MockResponse.Builder().code(500).body("boom").build();
    Path app = write("app.jar", "app");

    CodesignException e = assertThrows(CodesignException.class, () -> sign(Map.of(app, app), null));

    assertEquals(500, e.httpStatus());
  }

  @Test
  void deniedRequestWritesNothing() throws Exception {
    finalStatus = "Denied";
    Path app = write("app.jar", "app");

    SigningRequestStatus status = sign(Map.of(app, app), null);

    assertTrue(status.isDenied());
    assertEquals("app", Files.readString(app));
    assertTrue(
        requests.stream().noneMatch(r -> r.getUrl().encodedPath().endsWith("/SignedArtifact")));
  }

  @Test
  void duplicateFileNamesAreRejectedBeforeUploading() throws Exception {
    Path first = write("a/app.jar", "a");
    Path second = write("b/app.jar", "b");
    Map<Path, Path> files = new LinkedHashMap<>();
    files.put(first, first);
    files.put(second, second);

    CodesignException e = assertThrows(CodesignException.class, () -> sign(files, null));

    assertTrue(e.getMessage().contains("names must be unique"), e.getMessage());
    assertTrue(requests.isEmpty());
  }

  @Test
  void missingSignedEntryWritesNothing() throws Exception {
    signer = zip -> zipOf(Map.of("app.jar", "signed:app"));
    Path app = write("app.jar", "app");
    Path tool = write("tool.jar", "tool");
    Map<Path, Path> files = new LinkedHashMap<>();
    files.put(app, app);
    files.put(tool, tool);

    CodesignException e = assertThrows(CodesignException.class, () -> sign(files, null));

    assertEquals("The signed artifact does not contain: tool.jar", e.getMessage());
    assertEquals("app", Files.readString(app));
  }

  @Test
  void unexpectedEntriesAreIgnored() throws Exception {
    Map<String, String> signed = new LinkedHashMap<>();
    signed.put("../evil.jar", "evil");
    signed.put("app.jar", "signed:app");
    signed.put("tool.jar", "signed:tool");
    signer = zip -> zipOf(signed);
    Path app = write("app.jar", "app");
    Path tool = write("tool.jar", "tool");
    Map<Path, Path> files = new LinkedHashMap<>();
    files.put(app, app);
    files.put(tool, tool);

    sign(files, null);

    assertEquals("signed:app", Files.readString(app));
    assertFalse(Files.exists(tempDir.resolve("../evil.jar")));
    assertTrue(logLines.contains("Ignoring unexpected entry in the signed artifact: ../evil.jar"));
  }

  @Test
  void twirpErrorIsReported() throws Exception {
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            return json(401, "{\"code\":\"unauthenticated\",\"msg\":\"token expired\"}");
          }
        });
    Path app = write("app.jar", "app");

    CodesignException e = assertThrows(CodesignException.class, () -> sign(Map.of(app, app), null));

    assertEquals(
        "GitHub artifact upload failed: CreateArtifact returned HTTP 401: token expired"
            + " (unauthenticated)",
        e.getMessage());
  }

  @Test
  void blobErrorDoesNotLeakUploadUrl() throws Exception {
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            if (request.getUrl().encodedPath().endsWith("CreateArtifact")) {
              return json(
                  200,
                  "{\"ok\":true,\"signedUploadUrl\":\"" + server.url("/blob?sig=SECRET") + "\"}");
            }
            return new MockResponse.Builder()
                .code(403)
                .body("<Error><Code>AuthenticationFailed</Code><Url>?sig=SECRET</Url></Error>")
                .build();
          }
        });
    Path app = write("app.jar", "app");

    CodesignException e = assertThrows(CodesignException.class, () -> sign(Map.of(app, app), null));

    assertEquals(
        "GitHub artifact upload failed: the artifact storage returned HTTP 403"
            + " (AuthenticationFailed)",
        e.getMessage());
  }

  @Test
  void backendIdsComeFromTheResultsScope() throws Exception {
    assertArrayEquals(
        new String[] {"run-backend", "job-backend"},
        GitHubArtifactUploader.backendIds(runtimeToken()));
    assertThrows(CodesignException.class, () -> GitHubArtifactUploader.backendIds("not-a-jwt"));
    assertThrows(
        CodesignException.class,
        () -> GitHubArtifactUploader.backendIds(jwt("{\"scp\":\"Actions.GenericRead\"}")));
    assertThrows(
        CodesignException.class,
        () -> GitHubArtifactUploader.backendIds(jwt("{\"scp\":\"Actions.Results:only-one\"}")));
  }

  @Test
  void contextListsEveryMissingVariable() {
    CodesignException e =
        assertThrows(
            CodesignException.class,
            () ->
                GitHubActionsContext.fromEnvironment(
                    Map.of("GITHUB_RUN_ID", "1", "GITHUB_REPOSITORY", "o/r", "GITHUB_TOKEN", " ")
                        ::get));
    assertTrue(
        e.getMessage().contains("ACTIONS_RESULTS_URL, ACTIONS_RUNTIME_TOKEN, GITHUB_TOKEN"),
        e.getMessage());
  }

  @Test
  void contextToStringHidesTokens() {
    String text =
        new GitHubActionsContext("https://r", "rt-secret", "1", "o/r", "gh-secret").toString();
    assertFalse(text.contains("secret"), text);
  }

  @Test
  void connectorUrlMustUseHttps() throws Exception {
    Path app = write("app.jar", "app");
    SigningWorkflow workflow = new SigningWorkflow(client, Duration.ofMillis(10), logLines::add);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            workflow.signViaGitHubActions(
                context(), "http://connector.example", "p", "s", null, null, Map.of(app, app)));
  }

  @Test
  void trailingSlashesOfConnectorUrlAreIgnored() throws Exception {
    Path app = write("app.jar", "app");
    SigningWorkflow workflow = new SigningWorkflow(client, Duration.ofMillis(10), logLines::add);

    workflow.signViaGitHubActions(
        context(),
        server.url(CONNECTOR_PATH).toString() + "//",
        "proj",
        "policy",
        null,
        null,
        Map.of(app, app));

    assertEquals("signed:app", Files.readString(app));
  }

  private SigningRequestStatus sign(Map<Path, Path> files, Map<String, String> parameters)
      throws CodesignException, IOException {
    SigningWorkflow workflow =
        new SigningWorkflow(client, Duration.ofMillis(10), Duration.ofSeconds(30), logLines::add);
    return workflow.signViaGitHubActions(
        context(),
        server.url(CONNECTOR_PATH).toString(),
        "proj",
        "policy",
        "jar-in-zip",
        parameters,
        files);
  }

  private GitHubActionsContext context() {
    return new GitHubActionsContext(
        server.url("/_apis/results/").toString(),
        runtimeToken(),
        "12345",
        "eclipse-csi/codesign-tools",
        "gh-token");
  }

  private static String runtimeToken() {
    return jwt(
        "{\"scp\":\"Actions.ExampleScope Actions.Results:run-backend:job-backend\",\"exp\":1}");
  }

  private static String jwt(String payload) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8))
        + "."
        + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + ".signature";
  }

  private Path write(String relative, String content) throws IOException {
    Path file = tempDir.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
    return file;
  }

  private RecordedRequest request(String path) {
    return requests.stream()
        .filter(r -> r.getUrl().encodedPath().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No request to " + path));
  }

  private static JsonObject body(RecordedRequest request) {
    return JsonParser.parseString(request.getBody().utf8()).getAsJsonObject();
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build();
  }

  /** Prefixes the content of the file, or of every entry of a zip, with {@code signed:}. */
  private static byte[] fakeSign(byte[] zip) {
    if (zip.length < 2 || zip[0] != 'P' || zip[1] != 'K') {
      return ("signed:" + new String(zip, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }
    Map<String, String> signed = new LinkedHashMap<>();
    unzip(zip).forEach((name, content) -> signed.put(name, "signed:" + content));
    return zipOf(signed);
  }

  private static Map<String, String> unzip(byte[] zip) {
    Map<String, String> entries = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return entries;
  }

  private static byte[] zipOf(Map<String, String> entries) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream out = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, String> entry : entries.entrySet()) {
        out.putNextEntry(new ZipEntry(entry.getKey()));
        out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return bytes.toByteArray();
  }
}
