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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okio.Buffer;
import org.eclipse.csi.codesign.CodesignException;
import org.eclipse.csi.codesign.GitHubActionsContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Tests {@code sign --submission-mode github-actions} against a mock of every service involved. */
class SignCommandGitHubActionsTest {

  private static final String TWIRP = "/twirp/github.actions.results.api.v1.ArtifactService/";

  private MockWebServer server;
  private final List<String> paths = new CopyOnWriteArrayList<>();
  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();
  private byte[] uploadedZip;
  private GitHubActionsContext context;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            paths.add(request.getMethod() + " " + request.getUrl().encodedPath());
            return SignCommandGitHubActionsTest.this.dispatch(request);
          }
        });
    server.start();
    Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
    String runtimeToken =
        "e30."
            + base64.encodeToString(
                "{\"scp\":\"Actions.Results:run:job\"}".getBytes(StandardCharsets.UTF_8))
            + ".sig";
    context =
        new GitHubActionsContext(server.url("/").toString(), runtimeToken, "1", "o/r", "gh-token");
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
    if (path.equals("/connector/org/SigningRequests")) {
      return json("{\"signingRequestId\":\"r1\",\"signingRequestUrl\":\"https://web/r1\"}");
    }
    if (path.equals("/Api/v1/org/SigningRequests/r1")) {
      return json(
          "{\"status\":\"Completed\",\"workflowStatus\":\"Completed\",\"isFinalStatus\":true,"
              + "\"signedArtifactLink\":\""
              + server.url("/Api/v1/org/SigningRequests/r1/SignedArtifact")
              + "\"}");
    }
    if (path.equals("/Api/v1/org/SigningRequests/r1/SignedArtifact")) {
      return new MockResponse.Builder()
          .code(200)
          .body(new Buffer().write(sign(uploadedZip)))
          .build();
    }
    return new MockResponse.Builder().code(404).build();
  }

  @Test
  void signsAllFilesInOneRequestIntoOutputDir() throws Exception {
    Path a = Files.writeString(tempDir.resolve("a.jar"), "a");
    Path b = Files.writeString(tempDir.resolve("b.jar"), "b");
    Path outDir = tempDir.resolve("out");

    int exitCode =
        cli(context)
            .execute(
                "sign",
                "-O",
                "org",
                "-p",
                "proj",
                "-s",
                "test-signing",
                "--artifact-configuration",
                "jar-in-zip",
                "--submission-mode",
                "github-actions",
                "--github-connector-url",
                server.url("/connector").toString(),
                "--base-url",
                server.url("/Api").toString(),
                "--description",
                "ignored",
                "--output-dir",
                outDir.toString(),
                a.toString(),
                b.toString());

    assertEquals(0, exitCode, err.toString());
    assertEquals("signed:a", Files.readString(outDir.resolve("a.jar")));
    assertEquals("signed:b", Files.readString(outDir.resolve("b.jar")));
    assertEquals(1, paths.stream().filter(p -> p.contains("/connector/")).count());
    assertTrue(out.toString().contains("Signed: " + outDir.resolve("b.jar")), out.toString());
    assertTrue(err.toString().contains("--description is ignored"), err.toString());
  }

  @Test
  void missingGitHubEnvironmentFailsBeforeAnyRequest() throws Exception {
    Path a = Files.writeString(tempDir.resolve("a.jar"), "a");

    int exitCode =
        cli(null)
            .execute(
                "sign",
                "-O",
                "org",
                "-p",
                "proj",
                "-s",
                "test-signing",
                "--submission-mode",
                "github-actions",
                "--force-overwrite",
                a.toString());

    assertNotEquals(0, exitCode);
    assertTrue(err.toString().contains("ACTIONS_RUNTIME_TOKEN"), err.toString());
    assertTrue(paths.isEmpty());
  }

  @Test
  void invalidSubmissionModeIsRejected() throws Exception {
    Path a = Files.writeString(tempDir.resolve("a.jar"), "a");

    int exitCode =
        cli(context)
            .execute(
                "sign",
                "-O",
                "org",
                "-p",
                "proj",
                "-s",
                "s",
                "--submission-mode",
                "jenkins",
                "--force-overwrite",
                a.toString());

    assertEquals(2, exitCode);
    assertTrue(err.toString().contains("Invalid --submission-mode 'jenkins'"), err.toString());
  }

  /** {@code null} context = the environment lacks the GitHub Actions variables. */
  private CommandLine cli(GitHubActionsContext gitHubContext) {
    SignCommand command =
        new SignCommand() {
          @Override
          String resolveToken() {
            return "token";
          }

          @Override
          GitHubActionsContext resolveGitHubActionsContext() throws CodesignException {
            if (gitHubContext == null) {
              return GitHubActionsContext.fromEnvironment(name -> null);
            }
            return gitHubContext;
          }
        };
    return new CommandLine(
            new CodesignCli(),
            new CommandLine.IFactory() {
              @Override
              @SuppressWarnings("unchecked")
              public <K> K create(Class<K> cls) throws Exception {
                return cls == SignCommand.class
                    ? (K) command
                    : CommandLine.defaultFactory().create(cls);
              }
            })
        .setOut(new PrintWriter(out, true))
        .setErr(new PrintWriter(err, true))
        .setExecutionExceptionHandler(new PrintExceptionMessageHandler());
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
        ZipOutputStream outZip = new ZipOutputStream(bytes)) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        outZip.putNextEntry(new ZipEntry(entry.getName()));
        outZip.write(
            ("signed:" + new String(in.readAllBytes(), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));
        outZip.closeEntry();
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return bytes.toByteArray();
  }
}
