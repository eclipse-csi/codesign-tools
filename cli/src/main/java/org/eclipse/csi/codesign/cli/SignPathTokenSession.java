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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Runs the <a href="https://docs.signpath.io/crypto-providers/macos">SignPath macOS
 * CryptoTokenKit</a> for the duration of a signing session.
 *
 * <p>The CryptoTokenKit is an app that publishes the certificates of a SignPath signing policy as
 * signing identities in the keychain; {@code codesign} and {@code productsign} then sign with them,
 * and SignPath signs each hash they send. The session:
 *
 * <ol>
 *   <li>downloads {@code SignPathCryptoTokenKit.dmg} (unless a preinstalled app is given), checks
 *       its SHA-256 when one is pinned, and checks that the disk image and the app carry a valid
 *       Developer ID signature of SignPath GmbH (Team ID {@value #SIGNPATH_TEAM_ID});
 *   <li>mounts the disk image read-only and launches the app with {@code open}, passing the API
 *       token in the {@code SIGNPATH_API_TOKEN} environment variable, so that it never appears on a
 *       command line;
 *   <li>on {@link #close()}, also run from a shutdown hook, stops the app, unmounts the disk image
 *       and deletes the download.
 * </ol>
 */
final class SignPathTokenSession implements AutoCloseable {

  static final String DEFAULT_DMG_URL =
      "https://download.signpath.io/cryptoproviders/macos-cryptotokenkit/2-latest/SignPathCryptoTokenKit.dmg";

  static final String DEFAULT_API_URL = "https://app.signpath.io/Api";

  /** Apple Team ID of SignPath GmbH, which signs and notarizes the CryptoTokenKit. */
  static final String SIGNPATH_TEAM_ID = "PDP2N2TC2T";

  /** Code requirement of the CryptoTokenKit disk image and app: Developer ID of SignPath GmbH. */
  static final String CODE_REQUIREMENT =
      "anchor apple generic and certificate leaf[subject.OU] = \"" + SIGNPATH_TEAM_ID + "\"";

  static final String APP_NAME = "SignPathCryptoTokenKit.app";

  static final String TOKEN_ENV_VAR = "SIGNPATH_API_TOKEN";

  private static final int LOG_TAIL_LINES = 30;

  /**
   * Settings of the session.
   *
   * @param organizationId SignPath organization ID
   * @param projectSlug SignPath project slug
   * @param signingPolicySlug SignPath signing policy slug
   * @param apiUrl SignPath API URL
   * @param apiToken API token of the CI user that submits the signing requests
   * @param dmgUrl HTTPS URL of {@code SignPathCryptoTokenKit.dmg}
   * @param dmgSha256 expected SHA-256 of the disk image, or {@code null} to rely on its code
   *     signature only
   * @param preinstalledApp path of an installed {@code SignPathCryptoTokenKit.app} to use instead
   *     of downloading it, or {@code null}
   */
  record Settings(
      String organizationId,
      String projectSlug,
      String signingPolicySlug,
      String apiUrl,
      String apiToken,
      String dmgUrl,
      String dmgSha256,
      Path preinstalledApp) {

    @Override
    public String toString() {
      return "Settings[organizationId="
          + organizationId
          + ", projectSlug="
          + projectSlug
          + ", signingPolicySlug="
          + signingPolicySlug
          + ", apiUrl="
          + apiUrl
          + ", apiToken=***, dmgUrl="
          + dmgUrl
          + ", dmgSha256="
          + dmgSha256
          + ", preinstalledApp="
          + preinstalledApp
          + "]";
    }
  }

  /** Downloads a URL to a file. Tests replace it to avoid network access. */
  @FunctionalInterface
  interface Downloader {
    void download(String url, Path destination) throws IOException;
  }

  private final ProcessRunner runner;
  private final Downloader downloader;
  private final Consumer<String> log;
  private final Settings settings;
  private final Path logDirectory;

  private final AtomicBoolean closed = new AtomicBoolean();
  private Thread shutdownHook;
  private Path workDir;
  private Path mountPoint;
  private Path app;
  private boolean mounted;
  private boolean launched;

  SignPathTokenSession(
      ProcessRunner runner,
      Downloader downloader,
      Consumer<String> log,
      Settings settings,
      Path logDirectory) {
    this.runner = runner;
    this.downloader = downloader;
    this.log = log;
    this.settings = settings;
    this.logDirectory = logDirectory;
  }

  /** Where the CryptoTokenKit writes its logs. */
  static Path defaultLogDirectory() {
    return Path.of(System.getProperty("user.home"), "Library", "Logs", "SignPathCryptoTokenKit");
  }

  /** Installs the CryptoTokenKit if needed, verifies it and launches it. */
  void start() throws IOException, InterruptedException, ToolException {
    shutdownHook = new Thread(this::close, "csi-codesign-ctk-cleanup");
    Runtime.getRuntime().addShutdownHook(shutdownHook);

    if (settings.preinstalledApp() != null) {
      app = settings.preinstalledApp().toAbsolutePath().normalize();
      if (!Files.isDirectory(app)) {
        throw new ToolException("SignPath CryptoTokenKit app not found: " + app);
      }
    } else {
      workDir = Files.createTempDirectory("csi-codesign-ctk-");
      Path dmg = workDir.resolve("SignPathCryptoTokenKit.dmg");
      log.accept("Downloading " + settings.dmgUrl());
      downloader.download(settings.dmgUrl(), dmg);
      if (settings.dmgSha256() != null) {
        String actual = sha256(dmg);
        if (!actual.equalsIgnoreCase(settings.dmgSha256().strip())) {
          throw new ToolException(
              "Checksum mismatch for "
                  + settings.dmgUrl()
                  + ": expected "
                  + settings.dmgSha256().strip()
                  + ", got "
                  + actual
                  + ".");
        }
      }
      verifySignature(dmg);
      mountPoint = workDir.resolve("mnt");
      Files.createDirectories(mountPoint);
      runner.runChecked(
          List.of(
              "hdiutil",
              "attach",
              "-readonly",
              "-nobrowse",
              "-noautoopen",
              "-quiet",
              "-mountpoint",
              mountPoint.toString(),
              dmg.toString()));
      mounted = true;
      app = mountPoint.resolve(APP_NAME);
      if (!Files.isDirectory(app)) {
        throw new ToolException(settings.dmgUrl() + " does not contain " + APP_NAME);
      }
    }
    verifySignature(app);

    List<String> command =
        new ArrayList<>(
            List.of(
                "open",
                "-n",
                "-g",
                "--env",
                TOKEN_ENV_VAR, // copied from the environment of open, see below
                app.toString(),
                "--args",
                "--organization-id",
                settings.organizationId(),
                "--project-slug",
                settings.projectSlug(),
                "--signing-policy-slug",
                settings.signingPolicySlug()));
    if (settings.apiUrl() != null && !DEFAULT_API_URL.equals(settings.apiUrl())) {
      command.add("--api-url");
      command.add(settings.apiUrl());
    }
    log.accept("Starting the SignPath CryptoTokenKit");
    runner.runChecked(command, Map.of(TOKEN_ENV_VAR, settings.apiToken()));
    launched = true;
  }

  /**
   * Describes the state of the CryptoTokenKit, for an error message: the smart card tokens the
   * system knows, and the end of the latest CryptoTokenKit log.
   */
  String diagnostics() {
    StringBuilder text = new StringBuilder();
    try {
      ProcessRunner.Result tokens = runner.run(List.of("security", "list-smartcards"));
      text.append("\nsecurity list-smartcards:").append(indent(tokens.output()));
    } catch (IOException e) {
      text.append("\nsecurity list-smartcards failed: ").append(e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    Optional<Path> latestLog = latestLog();
    if (latestLog.isPresent()) {
      try {
        List<String> lines = Files.readAllLines(latestLog.get());
        String tail =
            String.join(
                "\n", lines.subList(Math.max(0, lines.size() - LOG_TAIL_LINES), lines.size()));
        text.append("\nEnd of ").append(latestLog.get()).append(':').append(indent(mask(tail)));
      } catch (IOException e) {
        text.append("\nCould not read ")
            .append(latestLog.get())
            .append(": ")
            .append(e.getMessage());
      }
    } else {
      text.append("\nNo CryptoTokenKit log in ").append(logDirectory);
    }
    return text.toString();
  }

  /** Stops the CryptoTokenKit, unmounts its disk image and deletes the download. Idempotent. */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException alreadyShuttingDown) {
        // The hook runs anyway and returns immediately, as the session is closed.
      }
    }
    try {
      if (launched) {
        runner.run(List.of("pkill", "-f", extendedRegexQuote(app.toString())));
      }
      if (mounted
          && (runner.run(List.of("hdiutil", "detach", mountPoint.toString())).exitCode() == 0
              || runner
                      .run(List.of("hdiutil", "detach", "-force", mountPoint.toString()))
                      .exitCode()
                  == 0)) {
        mounted = false;
      }
    } catch (IOException e) {
      log.accept("[WARNING] Could not stop the SignPath CryptoTokenKit: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (mounted) {
      // Never walk into a disk image that is still mounted.
      log.accept(
          "[WARNING] Could not unmount " + mountPoint + "; leaving " + workDir + " in place");
    } else if (workDir != null) {
      deleteRecursively(workDir);
    }
  }

  private void verifySignature(Path path) throws IOException, InterruptedException, ToolException {
    ProcessRunner.Result result =
        runner.run(
            List.of("codesign", "--verify", "--strict", "-R=" + CODE_REQUIREMENT, path.toString()));
    if (result.exitCode() != 0) {
      throw new ToolException(
          path
              + " is not signed by SignPath GmbH (Team ID "
              + SIGNPATH_TEAM_ID
              + ")"
              + ProcessRunner.quote(result.output()));
    }
  }

  private Optional<Path> latestLog() {
    if (!Files.isDirectory(logDirectory)) {
      return Optional.empty();
    }
    try (Stream<Path> files = Files.list(logDirectory)) {
      return files
          .filter(Files::isRegularFile)
          .max(Comparator.comparing(SignPathTokenSession::lastModified));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private static long lastModified(Path file) {
    try {
      return Files.getLastModifiedTime(file).toMillis();
    } catch (IOException e) {
      return 0;
    }
  }

  private String mask(String text) {
    String token = settings.apiToken();
    return token == null || token.isEmpty() ? text : text.replace(token, "***");
  }

  private static String indent(String text) {
    String stripped = text == null ? "" : text.strip();
    return stripped.isEmpty() ? " (empty)" : "\n  " + stripped.replace("\n", "\n  ");
  }

  /** Quotes {@code text} so that {@code pkill -f} (POSIX extended regex) matches it literally. */
  static String extendedRegexQuote(String text) {
    StringBuilder quoted = new StringBuilder();
    for (char c : text.toCharArray()) {
      if ("\\.^$|?*+()[]{}".indexOf(c) >= 0) {
        quoted.append('\\');
      }
      quoted.append(c);
    }
    return quoted.toString();
  }

  /**
   * Fetches the X.509 certificate of the signing policy of {@code settings}, DER-encoded, from the
   * SignPath API endpoint the Crypto Providers use ({@code GET Cryptoki/MySigningPolicies}). It
   * only lists the policies the API token's user can submit to.
   */
  static byte[] fetchPolicyCertificate(Settings settings) throws IOException, ToolException {
    String base = settings.apiUrl() != null ? settings.apiUrl() : DEFAULT_API_URL;
    HttpUrl endpoint =
        HttpUrl.parse(base + "/v1/" + settings.organizationId() + "/Cryptoki/MySigningPolicies");
    if (endpoint == null || !endpoint.isHttps()) {
      throw new ToolException("Invalid SignPath API URL (HTTPS required): " + base);
    }
    HttpUrl url =
        endpoint
            .newBuilder()
            .addQueryParameter("projectSlug", settings.projectSlug())
            .addQueryParameter("signingPolicySlug", settings.signingPolicySlug())
            .build();
    Request request =
        new Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + settings.apiToken())
            .header("Accept", "application/json")
            .build();
    OkHttpClient client = new OkHttpClient.Builder().callTimeout(Duration.ofMinutes(1)).build();
    String body;
    int code;
    try (Response response = client.newCall(request).execute()) {
      code = response.code();
      body = response.body().string();
    } finally {
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
    }
    return parsePolicyCertificate(code, body, settings);
  }

  /** Extracts the certificate from a {@code Cryptoki/MySigningPolicies} response. */
  static byte[] parsePolicyCertificate(int code, String body, Settings settings)
      throws ToolException {
    String policy =
        "signing policy '"
            + settings.signingPolicySlug()
            + "' of project '"
            + settings.projectSlug()
            + "'";
    if (code == 401 || code == 403) {
      throw new ToolException(
          "SignPath rejected the API token (HTTP " + code + ") while looking up " + policy + ".");
    }
    if (code != 200) {
      throw new ToolException(
          "SignPath answered HTTP "
              + code
              + " while looking up "
              + policy
              + "."
              + ProcessRunner.quote(body));
    }
    try {
      JsonArray policies =
          JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("signingPolicies");
      if (policies != null) {
        for (JsonElement element : policies) {
          JsonObject candidate = element.getAsJsonObject();
          if (settings.signingPolicySlug().equals(string(candidate, "signingPolicySlug"))
              && settings.projectSlug().equals(string(candidate, "projectSlug"))) {
            String certificate = string(candidate, "certificateBytes");
            if (certificate == null || certificate.isBlank()) {
              throw new ToolException("The " + policy + " has no X.509 certificate.");
            }
            return Base64.getDecoder().decode(certificate);
          }
        }
      }
    } catch (IllegalStateException | IllegalArgumentException | JsonParseException e) {
      throw new ToolException(
          "Unexpected answer from SignPath while looking up "
              + policy
              + ": "
              + e.getMessage()
              + ProcessRunner.quote(body));
    }
    throw new ToolException(
        "SignPath does not list the "
            + policy
            + " for this API token: check the slugs, and that the token's user is a submitter"
            + " of the policy.");
  }

  private static String string(JsonObject object, String member) {
    JsonElement value = object.get(member);
    return value == null || value.isJsonNull() ? null : value.getAsString();
  }

  static String sha256(Path file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
    try (InputStream in = Files.newInputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = in.read(buffer)) > 0) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /** Downloads {@code url} over HTTPS. */
  static void httpsDownload(String url, Path destination) throws IOException {
    if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
      throw new IOException("Refusing to download over a non-HTTPS URL: " + url);
    }
    OkHttpClient client = new OkHttpClient.Builder().callTimeout(Duration.ofMinutes(5)).build();
    try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
      if (!response.isSuccessful()) {
        throw new IOException("GET " + url + " answered HTTP " + response.code());
      }
      try (InputStream body = response.body().byteStream()) {
        Files.copy(body, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
    }
  }

  private void deleteRecursively(Path root) {
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      log.accept("[WARNING] Could not delete " + root + ": " + e.getMessage());
    }
  }
}
