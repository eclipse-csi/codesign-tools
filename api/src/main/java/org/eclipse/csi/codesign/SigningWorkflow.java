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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Orchestrates the full signing workflow: submit → poll until a final status is reached → write the
 * signed files.
 *
 * <p>{@link #sign} submits to the SignPath REST API and {@link #signViaGitHubActions} through the
 * SignPath GitHub connector, which signing policies with trusted build system verification require.
 * Both send the files of one call in a single signing request, shaped the same way:
 *
 * <ul>
 *   <li><b>One file</b> is sent as is, so the artifact configuration describes the file itself
 *       (e.g. a {@code <jar-file>} or {@code <pe-file>} root element).
 *   <li><b>Several files</b> are packed at the root of a zip file. The artifact configuration must
 *       then have a {@code <zip-file>} root element whose rules match files at the zip root (e.g.
 *       {@code <jar-file path="*.jar">}), and the files must have distinct names.
 * </ul>
 *
 * <p>Both write each signed file to its output path, and nothing is written unless the request
 * completes and the signed artifact holds every file.
 *
 * <p>{@link #submitAndWait} is the lower-level building block: it submits one file to the REST API
 * and polls, and callers download the signed artifact via {@link
 * CodesignClient#downloadSignedArtifact(SigningRequestStatus, Path)}.
 */
public class SigningWorkflow {

  private static final String ZIP = "application/zip";
  private static final String OCTET_STREAM = "application/octet-stream";

  private final CodesignClient client;
  private final Duration pollInterval;
  private final Duration completionTimeout; // null = no limit
  private final Consumer<String> logger;

  /**
   * Creates a workflow that polls indefinitely (no completion deadline).
   *
   * @param client the SignPath API client
   * @param pollInterval how often to check signing status
   * @param logger consumer for informational progress messages; {@code null} = silent
   */
  public SigningWorkflow(CodesignClient client, Duration pollInterval, Consumer<String> logger) {
    this(client, pollInterval, null, logger);
  }

  /**
   * Creates a workflow with an optional completion timeout.
   *
   * @param client the SignPath API client
   * @param pollInterval how often to check signing status
   * @param completionTimeout maximum time to wait for each signing request to reach a final status,
   *     measured from its submission; {@code null} = no limit
   * @param logger consumer for informational progress messages; {@code null} = silent
   */
  public SigningWorkflow(
      CodesignClient client,
      Duration pollInterval,
      Duration completionTimeout,
      Consumer<String> logger) {
    this.client = client;
    this.pollInterval = pollInterval;
    this.completionTimeout = completionTimeout;
    this.logger = logger != null ? logger : ignored -> {};
  }

  /**
   * Submits an artifact for signing and polls until a final status is reached.
   *
   * @param projectId SignPath project slug
   * @param signingPolicy SignPath signing policy slug
   * @param artifactConfiguration optional artifact configuration slug; may be {@code null}
   * @param description optional signing request description; may be {@code null}
   * @param parameters optional custom key/value parameters; may be {@code null}
   * @param artifactPath path to the artifact to sign
   * @return the final (terminal) signing request status
   * @throws CodesignException if the API fails or the completion timeout elapses after submission
   *     before a final status is reached
   * @throws IOException on transport or thread-interruption errors
   */
  public SigningRequestStatus submitAndWait(
      String projectId,
      String signingPolicy,
      String artifactConfiguration,
      String description,
      Map<String, String> parameters,
      Path artifactPath)
      throws CodesignException, IOException {
    logger.accept("Artifact SHA-256 (pre-upload): " + sha256Hex(artifactPath));
    SigningRequest request =
        client.submit(
            projectId, signingPolicy, artifactConfiguration, description, parameters, artifactPath);
    logger.accept("Signing request submitted: " + request.statusUrl());
    return pollUntilFinal(request);
  }

  /**
   * Signs files through the SignPath REST API in a single signing request and writes the signed
   * files.
   *
   * <p>One file is uploaded as is; several files are packed at the root of a zip file (see the
   * {@linkplain SigningWorkflow class description}). Once the request completes, the signed
   * artifact is downloaded and each file is written to its output path, replacing any existing
   * file. Nothing is written unless the request completes and the signed artifact holds every file.
   *
   * @param projectId SignPath project slug
   * @param signingPolicy SignPath signing policy slug
   * @param artifactConfiguration optional artifact configuration slug; may be {@code null} to use
   *     the project default
   * @param description optional signing request description; may be {@code null}
   * @param parameters optional user-defined parameters; may be {@code null}
   * @param inputToOutput the files to sign, each mapped to where its signed version is written
   *     (which may be the input itself); iteration order is kept in the zip
   * @return the final (terminal) signing request status; the signed files were written only when it
   *     {@linkplain SigningRequestStatus#isCompleted() is completed}
   * @throws CodesignException if a file name occurs twice, if the API fails, if the completion
   *     timeout elapses, or if the signed artifact lacks a file
   * @throws IOException on transport, I/O or thread-interruption errors
   */
  public SigningRequestStatus sign(
      String projectId,
      String signingPolicy,
      String artifactConfiguration,
      String description,
      Map<String, String> parameters,
      Map<Path, Path> inputToOutput)
      throws CodesignException, IOException {
    return signBundled(
        inputToOutput,
        bundle ->
            client.submit(
                projectId,
                signingPolicy,
                artifactConfiguration,
                description,
                parameters,
                bundle.file()));
  }

  /**
   * Signs files through the SignPath GitHub connector, as required by signing policies with trusted
   * build system verification, and writes the signed files.
   *
   * <p>The files are stored as an artifact of the running GitHub Actions job (kept for one day),
   * which is submitted to the connector in a single signing request. One file is uploaded as is,
   * like {@code actions/upload-artifact} with {@code archive: false}; as GitHub requires for such
   * artifacts, the artifact is named after the file, so a workflow run cannot upload two files of
   * the same name this way, not even from different jobs. Several files are packed at the root of a
   * zip file, like {@code actions/upload-artifact} does by default. The request is shaped as with
   * {@link #sign}, so the same artifact configuration applies (see the {@linkplain SigningWorkflow
   * class description}).
   *
   * <p>Once the request completes, the signed artifact is downloaded and each file is written to
   * its output path, replacing any existing file. Nothing is written unless the request completes
   * and the signed artifact holds every file.
   *
   * @param context the GitHub Actions job the request is submitted from
   * @param connectorUrl the GitHub Actions endpoint of the SignPath pipeline connector
   * @param projectId SignPath project slug
   * @param signingPolicy SignPath signing policy slug
   * @param artifactConfiguration optional artifact configuration slug; may be {@code null} to use
   *     the project default
   * @param parameters optional user-defined parameters; may be {@code null}
   * @param inputToOutput the files to sign, each mapped to where its signed version is written
   *     (which may be the input itself); iteration order is kept in the zip
   * @return the final (terminal) signing request status; the signed files were written only when it
   *     {@linkplain SigningRequestStatus#isCompleted() is completed}
   * @throws CodesignException if a file name occurs twice or is not a valid artifact name, if
   *     GitHub, the connector or the API fail, if the completion timeout elapses, or if the signed
   *     artifact lacks a file
   * @throws IOException on transport, I/O or thread-interruption errors
   */
  public SigningRequestStatus signViaGitHubActions(
      GitHubActionsContext context,
      String connectorUrl,
      String projectId,
      String signingPolicy,
      String artifactConfiguration,
      Map<String, String> parameters,
      Map<Path, Path> inputToOutput)
      throws CodesignException, IOException {
    return signBundled(
        inputToOutput,
        bundle -> {
          long artifactId =
              new GitHubArtifactUploader(client.httpClient())
                  .upload(
                      context,
                      bundle.name(),
                      bundle.file(),
                      bundle.zipped() ? ZIP : OCTET_STREAM,
                      Instant.now().plus(Duration.ofDays(1)));
          logger.accept("Uploaded GitHub artifact " + bundle.name() + ", ID " + artifactId);
          return client.submitViaGitHubConnector(
              connectorUrl,
              context,
              artifactId,
              projectId,
              signingPolicy,
              artifactConfiguration,
              parameters,
              logger);
        });
  }

  /** What a signing request is made of: the file itself, or a zip of several files. */
  private record Bundle(String name, Path file, boolean zipped) {}

  /** Sends a {@link Bundle} in a signing request. */
  @FunctionalInterface
  private interface Submitter {
    SigningRequest submit(Bundle bundle) throws CodesignException, IOException;
  }

  /**
   * Bundles the files (the file itself, or a zip of several), submits the bundle, polls the request
   * and writes each signed file to its output path.
   */
  private SigningRequestStatus signBundled(Map<Path, Path> inputToOutput, Submitter submitter)
      throws CodesignException, IOException {
    Map<String, Path> inputsByName = new LinkedHashMap<>();
    Map<String, Path> outputsByName = new LinkedHashMap<>();
    for (Map.Entry<Path, Path> entry : inputToOutput.entrySet()) {
      String name = entry.getKey().getFileName().toString();
      Path previous = inputsByName.putIfAbsent(name, entry.getKey());
      if (previous != null) {
        throw new CodesignException(
            "Cannot sign "
                + previous
                + " and "
                + entry.getKey()
                + " in the same signing request: files are submitted at the root of a zip"
                + " file, so their names must be unique",
            null);
      }
      outputsByName.put(name, entry.getValue());
    }
    if (inputsByName.isEmpty()) {
      throw new IllegalArgumentException("No files to sign");
    }
    boolean single = inputsByName.size() == 1;

    // Work next to the outputs rather than in the shared temporary directory: the downloaded
    // signed files never sit in a publicly writable location, and stay on the output filesystem.
    Path firstOutputDir = outputsByName.values().iterator().next().toAbsolutePath().getParent();
    Files.createDirectories(firstOutputDir);
    Path workDir = Files.createTempDirectory(firstOutputDir, ".codesign-");
    try {
      for (Map.Entry<String, Path> entry : inputsByName.entrySet()) {
        logger.accept(
            "Artifact SHA-256 (pre-upload) " + entry.getKey() + ": " + sha256Hex(entry.getValue()));
      }

      Bundle bundle;
      if (single) {
        Map.Entry<String, Path> only = inputsByName.entrySet().iterator().next();
        bundle = new Bundle(only.getKey(), only.getValue(), false);
      } else {
        String name = "codesign-unsigned-" + UUID.randomUUID();
        Path zipFile = workDir.resolve(name + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
          for (Map.Entry<String, Path> entry : inputsByName.entrySet()) {
            zip.putNextEntry(new ZipEntry(entry.getKey()));
            Files.copy(entry.getValue(), zip);
            zip.closeEntry();
          }
        }
        bundle = new Bundle(name, zipFile, true);
        logger.accept(
            "Packed "
                + inputsByName.size()
                + " files at the root of a zip file, submitted in one signing request: the"
                + " artifact configuration must have a <zip-file> root element");
      }

      SigningRequest request = submitter.submit(bundle);
      logger.accept("Signing request submitted: " + request.statusUrl());
      SigningRequestStatus status = pollUntilFinal(request);
      if (!status.isCompleted()) {
        return status;
      }

      Path signedArtifact = workDir.resolve("signed");
      client.downloadSignedArtifact(status, signedArtifact);
      Map<String, Path> signedByName =
          single
              ? Map.of(bundle.name(), signedArtifact)
              : extractSignedFiles(signedArtifact, workDir.resolve("extracted"), outputsByName);
      moveIntoPlace(signedByName, outputsByName);
      return status;
    } finally {
      deleteRecursively(workDir);
    }
  }

  /**
   * Extracts the expected entries of the signed zip. Entries are only matched by exact file name,
   * so a crafted entry name cannot escape the output paths.
   */
  private Map<String, Path> extractSignedFiles(
      Path signedZip, Path extractDir, Map<String, Path> outputsByName)
      throws CodesignException, IOException {
    Files.createDirectories(extractDir);
    Map<String, Path> extracted = new LinkedHashMap<>();
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(signedZip))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        if (entry.isDirectory()
            || !outputsByName.containsKey(name)
            || extracted.containsKey(name)) {
          if (!entry.isDirectory()) {
            logger.accept("Ignoring unexpected entry in the signed artifact: " + name);
          }
          continue;
        }
        Path target = extractDir.resolve(Integer.toString(extracted.size()));
        Files.copy(zip, target);
        extracted.put(name, target);
      }
    }

    List<String> missing = new ArrayList<>();
    for (String name : outputsByName.keySet()) {
      if (!extracted.containsKey(name)) {
        missing.add(name);
      }
    }
    if (!missing.isEmpty()) {
      throw new CodesignException(
          "The signed artifact does not contain: " + String.join(", ", missing), null);
    }
    return extracted;
  }

  /** Moves each signed file to its output path, through a temporary sibling and atomic rename. */
  private void moveIntoPlace(Map<String, Path> signedByName, Map<String, Path> outputsByName)
      throws IOException {
    for (Map.Entry<String, Path> entry : outputsByName.entrySet()) {
      Path output = entry.getValue().toAbsolutePath().normalize();
      Files.createDirectories(output.getParent());
      Path tmp = output.resolveSibling(output.getFileName() + ".codesign-tmp");
      try {
        Files.copy(signedByName.get(entry.getKey()), tmp, StandardCopyOption.REPLACE_EXISTING);
        try {
          Files.move(
              tmp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(tmp);
      }
      logger.accept("Signed artifact SHA-256 " + output.getFileName() + ": " + sha256Hex(output));
    }
  }

  private static void deleteRecursively(Path dir) {
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      // best effort: the directory lives in the temporary directory
    }
  }

  private static String sha256Hex(Path path) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is guaranteed by the JDK spec", e);
    }
    try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
      in.transferTo(OutputStream.nullOutputStream());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private SigningRequestStatus pollUntilFinal(SigningRequest request)
      throws CodesignException, IOException {
    Instant deadline = completionTimeout != null ? Instant.now().plus(completionTimeout) : null;
    while (true) {
      SigningRequestStatus status = client.getStatus(request);
      logger.accept(
          "Signing status: " + status.status() + " (workflow: " + status.workflowStatus() + ")");

      if (status.isFinalStatus()) {
        return status;
      }

      if (deadline != null && Instant.now().isAfter(deadline)) {
        throw new CodesignException(
            "Timeout waiting for signing request to complete. "
                + "Last status: "
                + status.status()
                + "/"
                + status.workflowStatus()
                + " ["
                + request.statusUrl()
                + "]"
                + (status.webLink() != null
                    ? ". The signing request is still open in SignPath: " + status.webLink()
                    : ""),
            null);
      }

      try {
        Thread.sleep(pollInterval.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Signing poll interrupted", e);
      }
    }
  }
}
