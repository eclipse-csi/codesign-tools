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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
import okhttp3.MediaType;
import okhttp3.MultipartReader;
import okhttp3.OkHttpClient;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link SigningWorkflow#sign}, which submits through the SignPath REST API: one file as is,
 * several files zipped in one request.
 */
class DirectSigningTest {

  private static final String ORG = "test-org-id";
  private static final String SUBMIT_PATH = "/Api/v1/" + ORG + "/SigningRequests";

  private MockWebServer server;
  private CodesignClient client;
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final List<String> logLines = new ArrayList<>();

  /** Form fields of the submit request, and the uploaded artifact. */
  private final Map<String, String> submittedFields = new LinkedHashMap<>();

  private String uploadedFileName;
  private byte[] uploadedArtifact;

  /** Final status of the signing request. */
  private String finalStatus = "Completed";

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            requests.add(request);
            return DirectSigningTest.this.dispatch(request);
          }
        });
    server.start();
    client = new CodesignClient(new OkHttpClient(), server.url("/Api").toString(), ORG, "sp-token");
  }

  @AfterEach
  void tearDown() throws Exception {
    client.close();
    server.close();
  }

  private MockResponse dispatch(RecordedRequest request) {
    String path = request.getUrl().encodedPath();
    String apiBase = server.url("/Api").toString();
    if (path.equals(SUBMIT_PATH)) {
      readMultipart(request);
      return SignPathResponses.submitted(apiBase, ORG, "req-1");
    }
    if (path.equals(SUBMIT_PATH + "/req-1")) {
      return SignPathResponses.status(apiBase, ORG, "req-1", finalStatus, finalStatus, true);
    }
    if (path.equals(SUBMIT_PATH + "/req-1/SignedArtifact")) {
      return new MockResponse.Builder()
          .code(200)
          .body(new Buffer().write(fakeSign(uploadedArtifact)))
          .build();
    }
    return new MockResponse.Builder().code(404).build();
  }

  @Test
  void singleFileIsUploadedAsIs() throws Exception {
    Path exe = write("bin/tool.exe", "binary");
    Path out = tempDir.resolve("signed/tool.exe");

    SigningRequestStatus status = sign(Map.of(exe, out));

    assertTrue(status.isCompleted());
    assertEquals("signed:binary", Files.readString(out));
    assertEquals("binary", Files.readString(exe), "input untouched when the output differs");
    assertEquals("tool.exe", uploadedFileName);
    assertEquals("binary", new String(uploadedArtifact, StandardCharsets.UTF_8));
    assertEquals("proj", submittedFields.get("ProjectSlug"));
    assertEquals("policy", submittedFields.get("SigningPolicySlug"));
    assertEquals("config", submittedFields.get("ArtifactConfigurationSlug"));
    assertEquals("release build", submittedFields.get("Description"));
    assertEquals("1.2.3", submittedFields.get("Parameters[version]"));
    assertTrue(logLines.stream().noneMatch(line -> line.startsWith("Packed ")), logLines::toString);
  }

  @Test
  void severalFilesAreZippedInOneRequest() throws Exception {
    Path app = write("lib/app.jar", "app");
    Path tool = write("bin/tool.exe", "tool");
    Map<Path, Path> files = new LinkedHashMap<>();
    files.put(app, app);
    files.put(tool, tool);

    SigningRequestStatus status = sign(files);

    assertTrue(status.isCompleted());
    assertEquals("signed:app", Files.readString(app));
    assertEquals("signed:tool", Files.readString(tool));
    assertEquals(
        1,
        requests.stream().filter(r -> r.getUrl().encodedPath().equals(SUBMIT_PATH)).count(),
        "one signing request");
    assertTrue(uploadedFileName.startsWith("codesign-unsigned-"), uploadedFileName);
    assertTrue(uploadedFileName.endsWith(".zip"), uploadedFileName);
    assertEquals(List.of("app.jar", "tool.exe"), new ArrayList<>(unzip(uploadedArtifact).keySet()));
    assertTrue(
        logLines.stream().anyMatch(line -> line.startsWith("Packed 2 files")), logLines::toString);

    try (var leftovers = Files.list(app.getParent())) {
      assertEquals(List.of(app), leftovers.toList(), "work directory next to the output removed");
    }
  }

  @Test
  void duplicateFileNamesAreRejectedBeforeSubmitting() throws Exception {
    Path first = write("a/app.jar", "a");
    Path second = write("b/app.jar", "b");
    Map<Path, Path> files = new LinkedHashMap<>();
    files.put(first, first);
    files.put(second, second);

    CodesignException e = assertThrows(CodesignException.class, () -> sign(files));

    assertTrue(e.getMessage().contains("names must be unique"), e.getMessage());
    assertTrue(requests.isEmpty());
  }

  @Test
  void deniedRequestWritesNothing() throws Exception {
    finalStatus = "Denied";
    Path app = write("app.jar", "app");
    Path tool = write("tool.jar", "tool");
    Map<Path, Path> files = new LinkedHashMap<>();
    files.put(app, app);
    files.put(tool, tool);

    SigningRequestStatus status = sign(files);

    assertTrue(status.isDenied());
    assertEquals("app", Files.readString(app));
    assertEquals("tool", Files.readString(tool));
    assertTrue(
        requests.stream().noneMatch(r -> r.getUrl().encodedPath().endsWith("/SignedArtifact")));
  }

  private SigningRequestStatus sign(Map<Path, Path> files) throws CodesignException, IOException {
    SigningWorkflow workflow =
        new SigningWorkflow(client, Duration.ofMillis(10), Duration.ofSeconds(30), logLines::add);
    return workflow.sign(
        "proj", "policy", "config", "release build", Map.of("version", "1.2.3"), files);
  }

  private void readMultipart(RecordedRequest request) {
    String boundary = MediaType.get(request.getHeaders().get("Content-Type")).parameter("boundary");
    try (MultipartReader reader =
        new MultipartReader(new Buffer().write(request.getBody()), boundary)) {
      MultipartReader.Part part;
      while ((part = reader.nextPart()) != null) {
        String disposition = part.headers().get("Content-Disposition");
        String name = disposition.replaceAll(".*\\bname=\"([^\"]*)\".*", "$1");
        byte[] content = part.body().readByteArray();
        if (name.equals("Artifact")) {
          uploadedFileName = disposition.replaceAll(".*\\bfilename=\"([^\"]*)\".*", "$1");
          uploadedArtifact = content;
        } else {
          submittedFields.put(name, new String(content, StandardCharsets.UTF_8));
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private Path write(String relative, String content) throws IOException {
    Path file = tempDir.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
    return file;
  }

  /** Prefixes the content of the file, or of every entry of a zip, with {@code signed:}. */
  private static byte[] fakeSign(byte[] artifact) {
    if (artifact.length < 2 || artifact[0] != 'P' || artifact[1] != 'K') {
      return ("signed:" + new String(artifact, StandardCharsets.UTF_8))
          .getBytes(StandardCharsets.UTF_8);
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream out = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, String> entry : unzip(artifact).entrySet()) {
        out.putNextEntry(new ZipEntry(entry.getKey()));
        out.write(("signed:" + entry.getValue()).getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return bytes.toByteArray();
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
}
