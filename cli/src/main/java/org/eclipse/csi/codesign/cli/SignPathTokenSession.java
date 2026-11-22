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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *       token in a configuration file ({@code --config}) that only the current user can read, so
 *       that it never appears on a command line. {@code open} does not pass its own environment to
 *       the app, and {@code open --env} would need the token on its command line;
 *   <li>on {@link #close()}, also run from a shutdown hook, stops the app, unmounts the disk image
 *       and deletes the download and the configuration file.
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

  private static final String HDIUTIL = "hdiutil";

  /** Name of the configuration file of the CryptoTokenKit, in the private work directory. */
  static final String CONFIG_FILE = "config.json";

  private static final int LOG_TAIL_LINES = 40;

  private static final int OUTPUT_TAIL_LINES = 40;

  private static final int LOG_SEARCH_DEPTH = 4;

  /** The pluginkit protocol of CryptoTokenKit token extensions. */
  static final String TOKEN_EXTENSION_PROTOCOL = "com.apple.ctk-tokens";

  /** The path of a plug-in, at the end of a {@code pluginkit -m -v} line. */
  private static final Pattern PLUGIN_PATH =
      Pattern.compile("(?m)\\s(/[^\\r\\n]*\\.appex)[ \\t]*$");

  /** Prefix of the sandbox containers of the CryptoTokenKit app and of its token extension. */
  static final String CONTAINER_PREFIX = "io.signpath.";

  /**
   * How the token extension logs a refused hash signing request, e.g. {@code HTTP Request failed
   * with status code 403 {"title":"Forbidden","detail":"…"}}. It does not tell {@code codesign},
   * which then waits for ever.
   */
  private static final Pattern REFUSAL =
      Pattern.compile("HTTP Request failed with status code (\\d{3})[ \\t]*([^\\r\\n]*)");

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
  private final Path containersDirectory;

  private final AtomicBoolean closed = new AtomicBoolean();
  private Thread shutdownHook;
  private Path workDir;
  private Path mountPoint;
  private Path app;

  /** The token extensions of the mounted app that this session registered with pluginkit. */
  private final List<Path> registeredExtensions = new ArrayList<>();

  private boolean mounted;
  private boolean launched;

  /** The size of each log file before the launch: only what follows is about this session. */
  private final Map<Path, Long> logSizesAtLaunch = new HashMap<>();

  SignPathTokenSession(
      ProcessRunner runner,
      Downloader downloader,
      Consumer<String> log,
      Settings settings,
      Path logDirectory,
      Path containersDirectory) {
    this.runner = runner;
    this.downloader = downloader;
    this.log = log;
    this.settings = settings;
    this.logDirectory = logDirectory;
    this.containersDirectory = containersDirectory;
  }

  /** Where the CryptoTokenKit app writes its logs. */
  static Path defaultLogDirectory() {
    return Path.of(System.getProperty("user.home"), "Library", "Logs", "SignPathCryptoTokenKit");
  }

  /** Where the sandbox containers of apps are, among them that of the token extension. */
  static Path defaultContainersDirectory() {
    return Path.of(System.getProperty("user.home"), "Library", "Containers");
  }

  /** Installs the CryptoTokenKit if needed, verifies it and launches it. */
  void start() throws IOException, InterruptedException, ToolException {
    shutdownHook = new Thread(this::close, "csi-codesign-ctk-cleanup");
    Runtime.getRuntime().addShutdownHook(shutdownHook);

    workDir = PrivateFiles.createTempDirectory("csi-codesign-ctk-");
    if (settings.preinstalledApp() != null) {
      app = settings.preinstalledApp().toAbsolutePath().normalize();
      if (!Files.isDirectory(app)) {
        throw new ToolException("SignPath CryptoTokenKit app not found: " + app);
      }
    } else {
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
              HDIUTIL,
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
    if (mounted) {
      registerTokenExtensions();
    }
    Path config = writeConfig(workDir.resolve(CONFIG_FILE));

    List<String> command =
        new ArrayList<>(
            List.of(
                "open",
                "-n",
                "-g",
                app.toString(),
                "--args",
                "--config",
                config.toString(),
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
    for (Path logFile : logFiles()) {
      logSizesAtLaunch.put(logFile, Files.size(logFile));
    }
    log.accept("Starting the SignPath CryptoTokenKit");
    runner.runChecked(command);
    launched = true;
  }

  /**
   * Writes the configuration file of the CryptoTokenKit, readable by the current user only. It
   * holds the API token, which would otherwise have to go on a command line.
   */
  private Path writeConfig(Path file) throws IOException {
    JsonObject config = new JsonObject();
    config.addProperty("OrganizationId", settings.organizationId());
    config.addProperty("ApiToken", settings.apiToken());
    if (settings.apiUrl() != null) {
      config.addProperty("ApiUrl", settings.apiUrl());
    }
    config.addProperty("ProjectSlug", settings.projectSlug());
    config.addProperty("SigningPolicySlug", settings.signingPolicySlug());
    PrivateFiles.createFile(file);
    Files.writeString(file, config.toString());
    return file;
  }

  /**
   * Describes the state of the CryptoTokenKit, for an error message: the signing identities and
   * smart card tokens the system knows, the registered token extensions, and the end of the latest
   * logs of the app and of its token extension.
   */
  String diagnostics() {
    StringBuilder text = new StringBuilder();
    appendCommand(text, List.of("security", "find-identity"));
    appendCommand(text, List.of("security", "list-smartcards"));
    appendCommand(text, List.of("security", "export-smartcard", "-t", "identities"));
    appendCommand(text, List.of("system_profiler", "SPSmartCardsDataType"));
    appendCommand(text, List.of("pluginkit", "-m", "-v", "-p", "com.apple.ctk-tokens"));
    List<Path> logs = new ArrayList<>();
    latestFile(logDirectory).ifPresent(logs::add);
    // The token extension is sandboxed: it logs in its container.
    if (Files.isDirectory(containersDirectory)) {
      try (Stream<Path> containers = Files.list(containersDirectory)) {
        containers
            .filter(dir -> dir.getFileName().toString().startsWith(CONTAINER_PREFIX))
            .sorted()
            .forEach(dir -> latestFile(dir.resolve("Data/Library/Logs")).ifPresent(logs::add));
      } catch (IOException e) {
        text.append("\nCould not list ").append(containersDirectory).append(": ").append(e);
      }
    }
    if (logs.isEmpty()) {
      text.append("\nNo CryptoTokenKit log in ")
          .append(logDirectory)
          .append(" or in ")
          .append(containersDirectory)
          .append('/')
          .append(CONTAINER_PREFIX)
          .append('*');
    }
    for (Path logFile : logs) {
      try {
        text.append("\nEnd of ").append(logFile).append(':').append(indent(mask(tail(logFile))));
      } catch (IOException e) {
        text.append("\nCould not read ").append(logFile).append(": ").append(e.getMessage());
      }
    }
    return text.toString();
  }

  private void appendCommand(StringBuilder text, List<String> command) {
    text.append('\n').append(String.join(" ", command)).append(':');
    try {
      ProcessRunner.Result result = runner.run(command);
      text.append(indent(mask(lastLines(result.output(), OUTPUT_TAIL_LINES))));
      if (result.exitCode() != 0) {
        text.append("\n  (exit code ").append(result.exitCode()).append(')');
      }
    } catch (IOException e) {
      text.append(" failed: ").append(e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String tail(Path file) throws IOException {
    // Lenient decoding: a log that is not valid UTF-8 must not hide the rest of the diagnostics.
    return lastLines(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), LOG_TAIL_LINES);
  }

  private static String lastLines(String text, int count) {
    if (text == null) {
      return "";
    }
    List<String> lines = text.strip().lines().toList();
    return String.join("\n", lines.subList(Math.max(0, lines.size() - count), lines.size()));
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
      // A registration left behind would point to the unmounted disk image: macOS would then
      // keep electing that extension, which cannot start, over the one of the next session.
      for (Path extension : registeredExtensions) {
        runner.run(List.of("pluginkit", "-r", extension.toString()));
      }
      registeredExtensions.clear();
      if (mounted) {
        mounted = !unmount();
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

  /**
   * Makes macOS use the token extension of the mounted app. Each session mounts the disk image at a
   * new path, and macOS keeps the registration of an extension after its disk image is unmounted:
   * it would elect that stale extension, which cannot start, and the token of the session would
   * stay empty. Registrations of the SignPath extension whose path no longer exists are removed,
   * and those of the mounted app are added.
   */
  private void registerTokenExtensions() throws IOException, InterruptedException {
    ProcessRunner.Result listed =
        runner.run(List.of("pluginkit", "-m", "-v", "-p", TOKEN_EXTENSION_PROTOCOL));
    for (Path stale : staleTokenExtensions(listed.output(), app)) {
      log.accept("Removing the stale registration of " + stale);
      runner.run(List.of("pluginkit", "-r", stale.toString()));
    }
    Path plugIns = app.resolve("Contents/PlugIns");
    if (Files.isDirectory(plugIns)) {
      try (Stream<Path> entries = Files.list(plugIns)) {
        for (Path extension :
            entries.filter(path -> path.getFileName().toString().endsWith(".appex")).toList()) {
          runner.run(List.of("pluginkit", "-a", extension.toString()));
          registeredExtensions.add(extension);
        }
      }
    }
  }

  /**
   * The SignPath CryptoTokenKit extensions that {@code pluginkit -m -v} lists, outside {@code app},
   * whose path no longer exists.
   */
  static List<Path> staleTokenExtensions(String pluginkitOutput, Path app) {
    List<Path> stale = new ArrayList<>();
    Matcher matcher = PLUGIN_PATH.matcher(pluginkitOutput == null ? "" : pluginkitOutput);
    while (matcher.find()) {
      Path extension = Path.of(matcher.group(1));
      if (matcher.group(1).contains("/" + APP_NAME + "/Contents/PlugIns/")
          && !extension.startsWith(app)
          && !Files.exists(extension)) {
        stale.add(extension);
      }
    }
    return stale;
  }

  /** Unmounts the disk image, by force if needed. Returns whether it is unmounted. */
  private boolean unmount() throws IOException, InterruptedException {
    return runner.run(List.of(HDIUTIL, "detach", mountPoint.toString())).exitCode() == 0
        || runner.run(List.of(HDIUTIL, "detach", "-force", mountPoint.toString())).exitCode() == 0;
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

  /**
   * The reason SignPath gave for refusing a hash signing request of this session, as the token
   * extension logged it, e.g. {@code HTTP 403: The selected signing policy requires that the
   * signing request is submitted via a trusted build system.}
   */
  Optional<String> signPathRefusal() {
    for (Path logFile : logFiles()) {
      try {
        byte[] bytes = Files.readAllBytes(logFile);
        int from = (int) Math.min(bytes.length, logSizesAtLaunch.getOrDefault(logFile, 0L));
        Matcher matcher =
            REFUSAL.matcher(new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8));
        if (matcher.find()) {
          return Optional.of("HTTP " + matcher.group(1) + ": " + refusalDetail(matcher.group(2)));
        }
      } catch (IOException e) {
        // The file may have been rotated: look at the others.
      }
    }
    return Optional.empty();
  }

  /** The {@code detail} (or {@code title}) of a problem details body, or the body itself. */
  private String refusalDetail(String body) {
    try {
      JsonObject json = JsonParser.parseString(body).getAsJsonObject();
      String detail = string(json, "detail");
      String title = string(json, "title");
      return mask(detail != null ? detail : title != null ? title : body.strip());
    } catch (IllegalStateException | JsonParseException e) {
      return mask(body.strip());
    }
  }

  /** The files of the app's log directory and of the SignPath containers' log directories. */
  private List<Path> logFiles() {
    List<Path> directories = new ArrayList<>(List.of(logDirectory));
    if (Files.isDirectory(containersDirectory)) {
      try (Stream<Path> containers = Files.list(containersDirectory)) {
        containers
            .filter(dir -> dir.getFileName().toString().startsWith(CONTAINER_PREFIX))
            .map(dir -> dir.resolve("Data/Library/Logs"))
            .forEach(directories::add);
      } catch (IOException e) {
        // No containers to look at.
      }
    }
    List<Path> files = new ArrayList<>();
    for (Path directory : directories) {
      if (!Files.isDirectory(directory)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(directory, LOG_SEARCH_DEPTH)) {
        walk.filter(Files::isRegularFile).forEach(files::add);
      } catch (IOException | UncheckedIOException e) {
        // Skip a directory that cannot be read.
      }
    }
    return files;
  }

  /** The most recently modified file under {@code directory}, searched recursively. */
  private static Optional<Path> latestFile(Path directory) {
    if (!Files.isDirectory(directory)) {
      return Optional.empty();
    }
    try (Stream<Path> files = Files.walk(directory, LOG_SEARCH_DEPTH)) {
      return files
          .filter(Files::isRegularFile)
          .max(Comparator.comparing(SignPathTokenSession::lastModified));
    } catch (IOException | UncheckedIOException e) {
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
            // All the policies of the project, so that an error can list the usable ones.
            .addQueryParameter("projectSlug", settings.projectSlug())
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
    checkStatus(code, body, policy);
    List<String> listed = new ArrayList<>();
    for (JsonObject candidate : signingPolicies(body, policy)) {
      String project = string(candidate, "projectSlug");
      String slug = string(candidate, "signingPolicySlug");
      if (settings.signingPolicySlug().equals(slug) && settings.projectSlug().equals(project)) {
        return certificate(candidate, body, policy);
      }
      listed.add(project + "/" + slug);
    }
    // Observed on 2026-11-22: SignPath leaves the policies with origin verification out of this
    // list, so the CryptoTokenKit cannot use them, while trusted build system verification alone
    // does not hide a policy.
    throw new ToolException(
        "SignPath does not list the "
            + policy
            + " for hash signing with this API token. Check the slugs, that the token's user is a"
            + " submitter of the policy, and that the policy does not require origin"
            + " verification: SignPath does not offer such policies to the CryptoTokenKit."
            + " Policies listed for this token: "
            + (listed.isEmpty() ? "none" : String.join(", ", listed))
            + ".");
  }

  private static void checkStatus(int code, String body, String policy) throws ToolException {
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
  }

  /** The {@code signingPolicies} of a {@code Cryptoki/MySigningPolicies} response. */
  private static List<JsonObject> signingPolicies(String body, String policy) throws ToolException {
    try {
      JsonArray policies =
          JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("signingPolicies");
      List<JsonObject> result = new ArrayList<>();
      if (policies != null) {
        for (JsonElement element : policies) {
          result.add(element.getAsJsonObject());
        }
      }
      return result;
    } catch (IllegalStateException | JsonParseException e) {
      throw unexpectedAnswer(body, policy, e);
    }
  }

  /** The decoded {@code certificateBytes} of a signing policy. */
  private static byte[] certificate(JsonObject signingPolicy, String body, String policy)
      throws ToolException {
    try {
      String certificate = string(signingPolicy, "certificateBytes");
      if (certificate == null || certificate.isBlank()) {
        throw new ToolException("The " + policy + " has no X.509 certificate.");
      }
      return Base64.getDecoder().decode(certificate);
    } catch (IllegalArgumentException e) {
      throw unexpectedAnswer(body, policy, e);
    }
  }

  private static ToolException unexpectedAnswer(String body, String policy, RuntimeException e) {
    return new ToolException(
        "Unexpected answer from SignPath while looking up "
            + policy
            + ": "
            + e.getMessage()
            + ProcessRunner.quote(body));
  }

  private static String string(JsonObject object, String member) {
    JsonElement value = object.get(member);
    return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
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
