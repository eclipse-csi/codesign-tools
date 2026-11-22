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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Notarizes signed macOS apps, disk images and installer packages with Apple's notary service, then
 * staples the ticket to them and checks that Gatekeeper accepts them.
 *
 * <p>Authenticates with an App Store Connect API key (from the {@code CSI_CODESIGN_NOTARY_KEY*}
 * environment variables or options) or with a {@code notarytool} keychain profile. Apple ID
 * passwords are not supported.
 */
@Command(
    name = "notarize",
    description = {
      "Notarize signed macOS apps, disk images and installer packages, staple the tickets and"
          + " check them with Gatekeeper.",
      "Authenticates with an App Store Connect API key, or a notarytool keychain profile."
    },
    sortOptions = false,
    usageHelpAutoWidth = true,
    mixinStandardHelpOptions = true)
class MacOsNotarizeCommand implements Callable<Integer> {

  static final String KEY_ENV_VAR = "CSI_CODESIGN_NOTARY_KEY";

  private static final String XCRUN = "xcrun";

  private static final String MESSAGE = "message";

  private static final String ISSUES = "issues";
  static final String KEY_ID_ENV_VAR = "CSI_CODESIGN_NOTARY_KEY_ID";
  static final String ISSUER_ENV_VAR = "CSI_CODESIGN_NOTARY_ISSUER";

  @Spec CommandSpec spec;

  @Parameters(
      arity = "1..*",
      paramLabel = "<path>",
      description =
          "Signed apps (.app), disk images (.dmg), installer packages (.pkg) or zip files to"
              + " notarize. An app is zipped for submission and the app itself is stapled; a zip"
              + " file cannot be stapled.")
  List<Path> inputs;

  @Option(
      names = "--notary-key",
      paramLabel = "<file>",
      description =
          "App Store Connect API private key (.p8 file). Default: the key content in the "
              + KEY_ENV_VAR
              + " environment variable.")
  Path notaryKey;

  @Option(
      names = "--notary-key-id",
      paramLabel = "<id>",
      description =
          "App Store Connect API key ID. Default: the " + KEY_ID_ENV_VAR + " environment variable.")
  String notaryKeyId;

  @Option(
      names = "--notary-issuer",
      paramLabel = "<uuid>",
      description =
          "App Store Connect API issuer ID, for team keys. Default: the "
              + ISSUER_ENV_VAR
              + " environment variable.")
  String notaryIssuer;

  @Option(
      names = "--keychain-profile",
      paramLabel = "<name>",
      description =
          "notarytool keychain profile (see xcrun notarytool store-credentials), instead of an"
              + " API key.")
  String keychainProfile;

  @Option(
      names = "--wait-for-completion-timeout",
      paramLabel = "<seconds>",
      defaultValue = "1800",
      description =
          "Maximum seconds to wait for Apple to process each submission"
              + " (default: ${DEFAULT-VALUE}).")
  int waitForCompletionTimeout;

  @Option(
      names = "--no-staple",
      description = "Do not staple the notarization ticket (nor validate the stapled ticket).")
  boolean noStaple;

  @Option(
      names = {"--verbose", "-v"},
      description = "Show each notarytool invocation.")
  boolean verbose;

  /** What a {@code <path>} argument is, which decides how it is submitted and checked. */
  enum Kind {
    APP,
    DISK_IMAGE,
    INSTALLER_PACKAGE,
    ZIP
  }

  @Override
  public Integer call() throws Exception {
    if (!isMacOs()) {
      throw new ToolException("csi-codesign macos notarize runs on macOS only.");
    }
    List<Path> targets = new ArrayList<>();
    List<Kind> kinds = new ArrayList<>();
    for (Path input : inputs) {
      Path path = input.toAbsolutePath().normalize();
      targets.add(path);
      kinds.add(classify(path));
    }

    ProcessRunner runner = processRunner();
    Path workDir = PrivateFiles.createTempDirectory("csi-codesign-notarize-");
    try {
      List<String> credentials = credentials(workDir);
      for (int i = 0; i < targets.size(); i++) {
        notarize(runner, workDir, credentials, targets.get(i), kinds.get(i));
      }
    } finally {
      deleteRecursively(workDir);
    }
    return 0;
  }

  private Kind classify(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && name.endsWith(".app")) {
      return Kind.APP;
    }
    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      if (name.endsWith(".dmg")) {
        return Kind.DISK_IMAGE;
      }
      if (name.endsWith(".pkg")) {
        return Kind.INSTALLER_PACKAGE;
      }
      if (name.endsWith(".zip")) {
        return Kind.ZIP;
      }
    }
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new ParameterException(spec.commandLine(), "Not found: " + path);
    }
    throw new ParameterException(
        spec.commandLine(), "Cannot notarize " + path + ": not a .app, .dmg, .pkg or .zip.");
  }

  /** The notarytool authentication options. Writes the API key from the environment to a file. */
  private List<String> credentials(Path workDir) throws IOException {
    if (keychainProfile != null) {
      if (notaryKey != null || notaryKeyId != null || notaryIssuer != null) {
        throw new ParameterException(
            spec.commandLine(),
            "--keychain-profile cannot be combined with --notary-key, --notary-key-id or"
                + " --notary-issuer.");
      }
      return List.of("--keychain-profile", keychainProfile);
    }
    String keyId = notaryKeyId != null ? notaryKeyId : nonBlank(getenv(KEY_ID_ENV_VAR));
    String issuer = notaryIssuer != null ? notaryIssuer : nonBlank(getenv(ISSUER_ENV_VAR));
    Path keyFile = notaryKey;
    if (keyFile == null) {
      String keyContent = getenv(KEY_ENV_VAR); // written as is: it is a PEM document
      if (keyContent != null && !keyContent.isBlank()) {
        keyFile = workDir.resolve("AuthKey.p8");
        Files.writeString(keyFile, keyContent, StandardCharsets.UTF_8);
      }
    } else if (!Files.isRegularFile(keyFile)) {
      throw new ParameterException(spec.commandLine(), "Notary key file not found: " + keyFile);
    }
    if (keyFile == null || keyId == null) {
      throw new ParameterException(
          spec.commandLine(),
          "No notarization credentials. Provide an App Store Connect API key ("
              + KEY_ENV_VAR
              + " or --notary-key, "
              + KEY_ID_ENV_VAR
              + " or --notary-key-id, and for team keys "
              + ISSUER_ENV_VAR
              + " or --notary-issuer), or --keychain-profile.");
    }
    List<String> credentials =
        new ArrayList<>(List.of("--key", keyFile.toAbsolutePath().toString(), "--key-id", keyId));
    if (issuer != null) {
      credentials.add("--issuer");
      credentials.add(issuer);
    }
    return credentials;
  }

  private void notarize(
      ProcessRunner runner, Path workDir, List<String> credentials, Path target, Kind kind)
      throws IOException, InterruptedException, ToolException {
    Path submission = target;
    if (kind == Kind.APP) {
      submission = workDir.resolve(target.getFileName() + ".zip");
      runner.runChecked(
          List.of(
              "ditto",
              "-c",
              "-k",
              "--sequesterRsrc",
              "--keepParent",
              target.toString(),
              submission.toString()));
    }

    out("Submitting " + target + " to the Apple notary service");
    List<String> submit =
        new ArrayList<>(
            List.of(
                XCRUN,
                "notarytool",
                "submit",
                submission.toString(),
                "--wait",
                "--timeout",
                Math.max(1, waitForCompletionTimeout) + "s",
                "--output-format",
                "json"));
    submit.addAll(credentials);
    verboseOut(String.join(" ", submit));
    ProcessRunner.Result result = runner.run(submit);
    JsonObject status = parseJson(result.output());
    String id = status != null ? string(status, "id") : null;
    String state = status != null ? string(status, "status") : null;
    if (!"Accepted".equals(state)) {
      StringBuilder message =
          new StringBuilder("Notarization of ")
              .append(target)
              .append(state != null ? " ended with status " + state : " failed");
      if (status != null && string(status, MESSAGE) != null) {
        message.append(": ").append(string(status, MESSAGE));
      }
      if (id != null) {
        message
            .append(" (submission ")
            .append(id)
            .append(')')
            .append(issues(runner, id, credentials));
      } else {
        message.append(ProcessRunner.quote(result.output()));
      }
      throw new ToolException(message.toString());
    }
    out("Notarized: " + target + " (submission " + id + ")");

    if (kind == Kind.ZIP) {
      return; // Apple cannot staple zip files, and Gatekeeper does not assess them.
    }
    if (!noStaple) {
      runner.runChecked(List.of(XCRUN, "stapler", "staple", target.toString()));
      runner.runChecked(List.of(XCRUN, "stapler", "validate", target.toString()));
      out("Stapled: " + target);
    }
    List<String> command = new ArrayList<>(List.of("spctl", "--assess", "--type"));
    command.addAll(
        switch (kind) {
          case APP -> List.of("execute");
          case DISK_IMAGE -> List.of("open", "--context", "context:primary-signature");
          case INSTALLER_PACKAGE -> List.of("install");
          case ZIP -> throw new IllegalStateException();
        });
    command.add("--verbose=2");
    command.add(target.toString());
    ProcessRunner.Result assessment = runner.run(command);
    if (assessment.exitCode() != 0) {
      throw new ToolException(
          "Gatekeeper rejects " + target + ProcessRunner.quote(assessment.output()));
    }
  }

  /** Fetches the notarization log of a submission and formats its issues. */
  private String issues(ProcessRunner runner, String id, List<String> credentials)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of(XCRUN, "notarytool", "log", id));
    command.addAll(credentials);
    ProcessRunner.Result result = runner.run(command);
    JsonObject log = parseJson(result.output());
    if (log == null || !log.has(ISSUES) || !log.get(ISSUES).isJsonArray()) {
      return result.exitCode() == 0
          ? ProcessRunner.quote(result.output())
          : ". The notarization log could not be fetched" + ProcessRunner.quote(result.output());
    }
    StringBuilder text = new StringBuilder(". Issues:");
    for (JsonElement element : log.getAsJsonArray(ISSUES)) {
      if (!element.isJsonObject()) {
        continue;
      }
      JsonObject issue = element.getAsJsonObject();
      text.append("\n  [")
          .append(orElse(string(issue, "severity"), "issue"))
          .append("] ")
          .append(orElse(string(issue, "path"), "?"));
      if (string(issue, "architecture") != null) {
        text.append(" (").append(string(issue, "architecture")).append(')');
      }
      text.append(": ").append(orElse(string(issue, MESSAGE), ""));
    }
    return text.toString();
  }

  /** Parses the JSON object in {@code output}, ignoring any text around it. */
  static JsonObject parseJson(String output) {
    if (output == null) {
      return null;
    }
    int start = output.indexOf('{');
    int end = output.lastIndexOf('}');
    if (start < 0 || end < start) {
      return null;
    }
    try {
      JsonElement element = JsonParser.parseString(output.substring(start, end + 1));
      return element.isJsonObject() ? element.getAsJsonObject() : null;
    } catch (JsonParseException e) {
      return null;
    }
  }

  private static String string(JsonObject object, String member) {
    JsonElement element = object.get(member);
    return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
  }

  private static String orElse(String value, String fallback) {
    return value != null ? value : fallback;
  }

  private static String nonBlank(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static void deleteRecursively(Path root) throws IOException {
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private void out(String message) {
    spec.commandLine().getOut().println(message);
    spec.commandLine().getOut().flush();
  }

  private void verboseOut(String message) {
    if (verbose) {
      out(message);
    }
  }

  // --- Test seams ---------------------------------------------------------------------------

  /** Whether this process runs on macOS. Visible for testing. */
  boolean isMacOs() {
    return MacOsCommand.runningOnMacOs();
  }

  /** Runs the external tools. Visible for testing. */
  ProcessRunner processRunner() {
    return new SystemProcessRunner();
  }

  /** Reads an environment variable. Visible for testing. */
  String getenv(String name) {
    return System.getenv(name);
  }
}
