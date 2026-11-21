/*
 * Copyright (c) 2026 Eclipse Foundation AISBL and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.csi.codesign.mojo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
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
import org.apache.maven.plugin.MojoExecutionException;
import org.eclipse.csi.codesign.CodesignClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests {@code submissionMode=github-actions} against a mock of every service involved. */
class CodesignMojoGitHubActionsTest {

  private static final String TWIRP = "/twirp/github.actions.results.api.v1.ArtifactService/";

  private MockWebServer server;
  private final List<String> paths = new CopyOnWriteArrayList<>();
  private byte[] uploadedZip;
  private Map<String, String> environment;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            paths.add(request.getUrl().encodedPath());
            return CodesignMojoGitHubActionsTest.this.dispatch(request);
          }
        });
    server.start();
    Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
    environment =
        Map.of(
            "CSI_CODESIGN_API_TOKEN", "token",
            "ACTIONS_RESULTS_URL", server.url("/").toString(),
            "ACTIONS_RUNTIME_TOKEN",
                "e30."
                    + base64.encodeToString(
                        "{\"scp\":\"Actions.Results:run:job\"}".getBytes(StandardCharsets.UTF_8))
                    + ".sig",
            "GITHUB_RUN_ID", "1",
            "GITHUB_REPOSITORY", "o/r",
            "GITHUB_TOKEN", "gh-token");
  }

  @AfterEach
  void tearDown() throws IOException {
    server.close();
  }

  private MockResponse dispatch(RecordedRequest request) {
    String path = request.getUrl().encodedPath();
    if (path.equals(TWIRP + "CreateArtifact")) {
      return json("{\"ok\":true,\"signed_upload_url\":\"" + server.url("/blob?sig=x") + "\"}");
    }
    if (path.equals("/blob")) {
      uploadedZip = request.getBody().toByteArray();
      return new MockResponse.Builder().code(201).build();
    }
    if (path.equals(TWIRP + "FinalizeArtifact")) {
      return json("{\"ok\":true,\"artifact_id\":\"7\"}");
    }
    if (path.equals("/connector/test-org/SigningRequests")) {
      return json("{\"signingRequestId\":\"r1\"}");
    }
    if (path.equals("/Api/v1/test-org/SigningRequests/r1")) {
      return json(
          "{\"status\":\"Completed\",\"workflowStatus\":\"Completed\",\"isFinalStatus\":true,"
              + "\"signedArtifactLink\":\""
              + server.url("/Api/v1/test-org/SigningRequests/r1/SignedArtifact")
              + "\"}");
    }
    if (path.equals("/Api/v1/test-org/SigningRequests/r1/SignedArtifact")) {
      return new MockResponse.Builder()
          .code(200)
          .body(new Buffer().write(sign(uploadedZip)))
          .build();
    }
    return new MockResponse.Builder().code(404).build();
  }

  @Test
  void signsAllMatchingFilesInOneRequest() throws Exception {
    Files.writeString(tempDir.resolve("a.jar"), "a");
    Files.writeString(tempDir.resolve("b.jar"), "b");
    CodesignMojo mojo = createMojo("github-actions");

    mojo.execute();

    assertEquals("signed:a", Files.readString(tempDir.resolve("a.jar")));
    assertEquals("signed:b", Files.readString(tempDir.resolve("b.jar")));
    assertEquals(1, paths.stream().filter(p -> p.startsWith("/connector/")).count());
  }

  @Test
  void missingGitHubEnvironmentFailsBeforeAnyRequest() throws Exception {
    Files.writeString(tempDir.resolve("a.jar"), "a");
    environment = Map.of("CSI_CODESIGN_API_TOKEN", "token");
    CodesignMojo mojo = createMojo("github-actions");

    MojoExecutionException e = assertThrows(MojoExecutionException.class, mojo::execute);

    assertTrue(e.getMessage().contains("ACTIONS_RUNTIME_TOKEN"), e.getMessage());
    assertTrue(paths.isEmpty());
  }

  @Test
  void invalidSubmissionModeIsRejected() throws Exception {
    Files.writeString(tempDir.resolve("a.jar"), "a");
    CodesignMojo mojo = createMojo("jenkins");

    MojoExecutionException e = assertThrows(MojoExecutionException.class, mojo::execute);

    assertEquals(
        "Invalid submissionMode value: 'jenkins'. Expected one of: direct, github-actions",
        e.getMessage());
  }

  private CodesignMojo createMojo(String submissionMode) throws Exception {
    CodesignMojo mojo =
        new CodesignMojo(null) {
          @Override
          CodesignClient buildClient(CodesignClient.Config config) {
            try {
              Constructor<CodesignClient> ctor =
                  CodesignClient.class.getDeclaredConstructor(
                      OkHttpClient.class, String.class, String.class, String.class);
              ctor.setAccessible(true);
              return ctor.newInstance(
                  new OkHttpClient(), config.baseUrl(), config.organizationId(), config.apiToken());
            } catch (Exception e) {
              throw new RuntimeException("Test client creation failed", e);
            }
          }

          @Override
          String getEnvironmentVariable(String name) {
            return environment.get(name);
          }
        };
    setField(mojo, "organizationId", "test-org");
    setField(mojo, "projectId", "proj");
    setField(mojo, "signingPolicy", "test-signing");
    setField(mojo, "artifactConfiguration", "jar-in-zip");
    setField(mojo, "baseUrl", server.url("/Api").toString());
    setField(mojo, "submissionMode", submissionMode);
    setField(mojo, "gitHubConnectorUrl", server.url("/connector").toString());
    setField(mojo, "baseDirectory", tempDir.toString());
    setField(mojo, "includes", new String[] {"*.jar"});
    setField(mojo, "pollInterval", 1);
    setField(mojo, "retryInterval", 1);
    setField(mojo, "retryTimeout", 10);
    setField(mojo, "httpTimeout", 10);
    setField(mojo, "connectTimeout", 5);
    setField(mojo, "signProjectArtifact", "false");
    setField(mojo, "serverId", "codesign");
    setField(mojo, "configFile", tempDir.resolve("nonexistent-config.properties").toFile());
    return mojo;
  }

  private static void setField(Object target, String fieldName, Object value) throws Exception {
    Class<?> clazz = target.getClass();
    while (clazz != null) {
      try {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
        return;
      } catch (NoSuchFieldException e) {
        clazz = clazz.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName);
  }

  private static MockResponse json(String body) {
    return new MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build();
  }

  /** Prefixes every zip entry with {@code signed:}. */
  private static byte[] sign(byte[] zip) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip));
        ZipOutputStream out = new ZipOutputStream(bytes)) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        out.putNextEntry(new ZipEntry(entry.getName()));
        out.write(
            ("signed:" + new String(in.readAllBytes(), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return bytes.toByteArray();
  }
}
