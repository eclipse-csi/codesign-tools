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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Signs macOS code and installers with the Developer ID certificates of a SignPath signing policy,
 * through the SignPath macOS CryptoTokenKit.
 *
 * <ul>
 *   <li><b>Bundles</b> ({@code .app}, {@code .framework}...): every nested Mach-O binary and bundle
 *       is signed from the inside out (see {@link MacOsCodeScanner}), with the hardened runtime and
 *       a secure timestamp, as notarization requires. Entitlements go on the top-level bundle only.
 *   <li><b>Mach-O binaries</b> (executables, {@code .dylib}...): signed like a bundle's code.
 *   <li><b>Disk images</b> ({@code .dmg}): signed with a timestamp, without hardened runtime or
 *       entitlements, which do not apply to them.
 *   <li><b>Installer packages</b> ({@code .pkg}): signed with {@code productsign} and the Developer
 *       ID Installer identity.
 * </ul>
 *
 * <p>Each signature is verified before the command succeeds.
 */
@Command(
    name = "sign",
    description = {
      "Sign macOS apps, Mach-O binaries, disk images and installer packages with SignPath.",
      "Bundles are signed from the inside out, with the hardened runtime and a secure"
          + " timestamp, so that they can be notarized."
    },
    sortOptions = false,
    usageHelpAutoWidth = true,
    mixinStandardHelpOptions = true)
class MacOsSignCommand implements Callable<Integer> {

  static final String PROVIDER_SIGNPATH = "signpath";
  static final String PROVIDER_KEYCHAIN = "keychain";

  /** The identity {@code codesign} understands as "ad-hoc signature" (no certificate). */
  static final String AD_HOC_IDENTITY = "-";

  @Spec CommandSpec spec;

  @Parameters(
      arity = "1..*",
      paramLabel = "<path>",
      description =
          "Bundles (.app, .framework...), Mach-O binaries, disk images (.dmg) or installer"
              + " packages (.pkg) to sign, in this order.")
  List<Path> inputs;

  // --- Identities ---

  @Option(
      names = "--identity",
      paramLabel = "<name|sha1>",
      description =
          "Code signing identity (Developer ID Application certificate): a substring of its name,"
              + " or the SHA-1 hash of the certificate, as for codesign -s. With --token-provider"
              + " signpath, defaults to the certificate of the signing policy; with keychain, it is"
              + " required to sign bundles, binaries and disk images. '-' signs ad hoc (testing"
              + " only).")
  String identity;

  @Option(
      names = "--installer-identity",
      paramLabel = "<name|sha1>",
      description =
          "Installer signing identity (Developer ID Installer certificate) for .pkg files. With"
              + " --token-provider signpath, defaults to the certificate of the signing policy;"
              + " with keychain, it is required.")
  String installerIdentity;

  @Option(
      names = "--entitlements",
      paramLabel = "<plist>",
      description =
          "Entitlements of the top-level bundle or binary. Nested code is signed without"
              + " entitlements.")
  Path entitlements;

  // --- SignPath ---

  @Option(
      names = "--token-provider",
      paramLabel = "<provider>",
      defaultValue = PROVIDER_SIGNPATH,
      description =
          "Where the signing identities come from: '"
              + PROVIDER_SIGNPATH
              + "' runs the SignPath CryptoTokenKit for the policy given by --organization-id,"
              + " --project-id and --signing-policy; '"
              + PROVIDER_KEYCHAIN
              + "' uses identities already in the keychain (default: ${DEFAULT-VALUE}).")
  String tokenProvider;

  @Option(
      names = {"--organization-id", "-O"},
      paramLabel = "<id>",
      description = "SignPath organization identifier.")
  String organizationId;

  @Option(
      names = {"--project-id", "-p"},
      paramLabel = "<slug>",
      description =
          "SignPath project slug. Its default artifact configuration must be of type"
              + " 'hash signing data': the CryptoTokenKit always uses the default"
              + " configuration and cannot select another one.")
  String projectId;

  @Option(
      names = {"--signing-policy", "-s"},
      paramLabel = "<slug>",
      description = "SignPath signing policy slug.")
  String signingPolicy;

  @Option(
      names = "--config-file",
      paramLabel = "<file>",
      description =
          "Path to a properties file containing api.token"
              + " (default: ~/.config/eclipse-csi-codesign/config.properties).")
  Path configFile;

  @Option(
      names = "--base-url",
      paramLabel = "<url>",
      defaultValue = SignPathTokenSession.DEFAULT_API_URL,
      description = "SignPath API base URL (default: ${DEFAULT-VALUE}).")
  String baseUrl;

  @Option(
      names = "--identity-timeout",
      paramLabel = "<seconds>",
      defaultValue = "60",
      description =
          "Maximum seconds to wait for the CryptoTokenKit to publish the signing identities"
              + " (default: ${DEFAULT-VALUE}).")
  int identityTimeout;

  @Option(
      names = "--ctk-url",
      paramLabel = "<url>",
      defaultValue = SignPathTokenSession.DEFAULT_DMG_URL,
      description = "HTTPS URL of SignPathCryptoTokenKit.dmg (default: ${DEFAULT-VALUE}).")
  String ctkUrl;

  @Option(
      names = "--ctk-sha256",
      paramLabel = "<sha256>",
      description =
          "Expected SHA-256 of SignPathCryptoTokenKit.dmg. Its Developer ID signature by"
              + " SignPath GmbH is checked in any case.")
  String ctkSha256;

  @Option(
      names = "--ctk-app",
      paramLabel = "<path>",
      description = "Use this installed SignPathCryptoTokenKit.app instead of downloading it.")
  Path ctkApp;

  // --- Output ---

  @Option(
      names = "--output-dir",
      paramLabel = "<dir>",
      description = "Directory to copy the inputs to before signing them, under their names.")
  Path outputDir;

  @Option(
      names = "--force-overwrite",
      description =
          "Allow signing the inputs in place (required without --output-dir), or replacing"
              + " existing files in --output-dir.")
  boolean forceOverwrite;

  @Option(
      names = {"--verbose", "-v"},
      description = "Show each codesign invocation.")
  boolean verbose;

  /**
   * What {@code codesign --sign} receives: the SHA-1 hash of the identity {@code --identity}
   * selects, so that an older certificate with the same name cannot make the choice ambiguous, or
   * {@code -} for an ad-hoc signature.
   */
  private String codesignIdentity;

  /** What {@code productsign --sign} receives: the full name of the installer identity. */
  private String productsignIdentity;

  /** What a {@code <path>} argument is, which decides how it is signed. */
  enum Kind {
    BUNDLE,
    MACH_O,
    DISK_IMAGE,
    INSTALLER_PACKAGE
  }

  @Override
  public Integer call() throws Exception {
    if (!isMacOs()) {
      throw new ToolException("csi-codesign macos sign runs on macOS only.");
    }
    boolean viaSignPath = isSignPathProvider();
    Map<Path, Kind> targets = classifyInputs();
    validateOptions(targets, viaSignPath);

    ProcessRunner runner = processRunner();
    SignPathTokenSession session = null;
    try {
      if (viaSignPath) {
        SignPathTokenSession.Settings settings = signPathSettings();
        identitiesFromSigningPolicy(settings, targets);
        session = newTokenSession(runner, settings);
        session.start();
      }
      try {
        awaitIdentities(runner, targets, session != null);
        for (Map.Entry<Path, Kind> target : targets.entrySet()) {
          Path signed = prepareOutput(runner, target.getKey());
          sign(runner, signed, target.getValue());
          out("Signed: " + signed);
        }
      } catch (ToolException e) {
        if (session == null) {
          throw e;
        }
        // SignPath may refuse to sign only when codesign asks it to: its reason is in the
        // CryptoTokenKit log, not in the codesign output.
        throw new ToolException(e.getMessage() + session.diagnostics());
      }
    } finally {
      if (session != null) {
        session.close();
      }
    }
    return 0;
  }

  private boolean isSignPathProvider() {
    return switch (tokenProvider.strip().toLowerCase(Locale.ROOT)) {
      case PROVIDER_SIGNPATH -> true;
      case PROVIDER_KEYCHAIN -> false;
      default ->
          throw new ParameterException(
              spec.commandLine(),
              "Invalid --token-provider '"
                  + tokenProvider
                  + "'. Expected one of: "
                  + PROVIDER_SIGNPATH
                  + ", "
                  + PROVIDER_KEYCHAIN);
    };
  }

  private Map<Path, Kind> classifyInputs() throws IOException {
    Map<Path, Kind> targets = new LinkedHashMap<>();
    for (Path input : inputs) {
      Path path = input.toAbsolutePath().normalize();
      targets.put(path, classify(path));
    }
    return targets;
  }

  private Kind classify(Path path) throws IOException {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      if (MacOsCodeScanner.isBundle(path)) {
        return Kind.BUNDLE;
      }
    } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      if (name.endsWith(".dmg")) {
        return Kind.DISK_IMAGE;
      }
      if (name.endsWith(".pkg")) {
        return Kind.INSTALLER_PACKAGE;
      }
      if (MacOsCodeScanner.isMachO(path)) {
        return Kind.MACH_O;
      }
    } else {
      throw new ParameterException(spec.commandLine(), "Not found: " + path);
    }
    throw new ParameterException(
        spec.commandLine(),
        "Cannot sign "
            + path
            + ": not a bundle ("
            + String.join(", ", MacOsCodeScanner.BUNDLE_EXTENSIONS.stream().sorted().toList())
            + "), a Mach-O binary, a .dmg or a .pkg.");
  }

  private void validateOptions(Map<Path, Kind> targets, boolean viaSignPath) {
    if (outputDir == null && !forceOverwrite) {
      throw new ParameterException(
          spec.commandLine(),
          "No output specified. Use --output-dir <dir>, or --force-overwrite to sign in place.");
    }
    boolean signsCode = targets.values().stream().anyMatch(kind -> kind != Kind.INSTALLER_PACKAGE);
    boolean signsPackages = targets.containsValue(Kind.INSTALLER_PACKAGE);
    if (signsCode && identity == null && !viaSignPath) {
      throw new ParameterException(
          spec.commandLine(),
          "Signing bundles, Mach-O binaries and disk images with --token-provider "
              + PROVIDER_KEYCHAIN
              + " requires --identity.");
    }
    if (signsCode && signsPackages && viaSignPath) {
      // The CryptoTokenKit publishes the certificate of one signing policy, and a policy has one
      // certificate: Developer ID Application and Developer ID Installer need two policies.
      throw new ParameterException(
          spec.commandLine(),
          "Sign .pkg files in a separate invocation: a SignPath signing policy has a single"
              + " certificate, so the Developer ID Installer certificate has its own policy.");
    }
    if (signsPackages) {
      if (installerIdentity == null && !viaSignPath) {
        throw new ParameterException(
            spec.commandLine(),
            "Signing a .pkg with --token-provider "
                + PROVIDER_KEYCHAIN
                + " requires --installer-identity.");
      }
      if (AD_HOC_IDENTITY.equals(installerIdentity)) {
        throw new ParameterException(
            spec.commandLine(), "Installer packages cannot be signed ad hoc.");
      }
    }
    if (entitlements != null && !Files.isRegularFile(entitlements)) {
      throw new ParameterException(
          spec.commandLine(), "Entitlements file not found: " + entitlements);
    }
    if (outputDir != null) {
      List<Path> names = targets.keySet().stream().map(Path::getFileName).toList();
      if (names.stream().distinct().count() != names.size()) {
        throw new ParameterException(
            spec.commandLine(), "With --output-dir, the inputs must have distinct names.");
      }
    }
    if (viaSignPath) {
      List<String> missing = new ArrayList<>();
      if (organizationId == null) {
        missing.add("--organization-id");
      }
      if (projectId == null) {
        missing.add("--project-id");
      }
      if (signingPolicy == null) {
        missing.add("--signing-policy");
      }
      if (!missing.isEmpty()) {
        throw new ParameterException(
            spec.commandLine(),
            "--token-provider "
                + PROVIDER_SIGNPATH
                + " requires "
                + String.join(", ", missing)
                + ".");
      }
      if (AD_HOC_IDENTITY.equals(identity)) {
        throw new ParameterException(
            spec.commandLine(),
            "Ad-hoc signing (--identity -) does not use SignPath: add --token-provider "
                + PROVIDER_KEYCHAIN
                + ".");
      }
    }
  }

  private SignPathTokenSession.Settings signPathSettings() {
    String token = resolveToken();
    if (token == null) {
      Path effectiveConfigFile =
          configFile != null ? configFile : TokenResolver.DEFAULT_CONFIG_FILE;
      throw new ParameterException(
          spec.commandLine(),
          "No API token found. Provide it via one of the following (in priority order):\n"
              + "  1. CSI_CODESIGN_API_TOKEN environment variable\n"
              + "  2. api.token key in "
              + effectiveConfigFile
              + " (override with --config-file)");
    }
    return new SignPathTokenSession.Settings(
        organizationId, projectId, signingPolicy, baseUrl, token, ctkUrl, ctkSha256, ctkApp);
  }

  /**
   * Defaults {@code --identity} and {@code --installer-identity} to the SHA-1 hash of the signing
   * policy's certificate, the only one the CryptoTokenKit publishes for that policy.
   */
  private void identitiesFromSigningPolicy(
      SignPathTokenSession.Settings settings, Map<Path, Kind> targets)
      throws IOException, ToolException {
    boolean signsCode = targets.values().stream().anyMatch(kind -> kind != Kind.INSTALLER_PACKAGE);
    boolean signsPackages = targets.containsValue(Kind.INSTALLER_PACKAGE);
    if (!(signsCode && identity == null) && !(signsPackages && installerIdentity == null)) {
      return;
    }
    byte[] der = signingPolicyCertificate(settings);
    String sha1;
    String subject;
    try {
      X509Certificate certificate =
          (X509Certificate)
              CertificateFactory.getInstance("X.509")
                  .generateCertificate(new ByteArrayInputStream(der));
      subject = certificate.getSubjectX500Principal().getName();
      sha1 =
          HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(der));
    } catch (CertificateException | NoSuchAlgorithmException e) {
      throw new ToolException(
          "SignPath returned an invalid certificate for signing policy '"
              + signingPolicy
              + "': "
              + e.getMessage());
    }
    verboseOut(
        "Certificate of signing policy " + signingPolicy + ": " + subject + " (" + sha1 + ")");
    if (signsCode && identity == null) {
      identity = sha1;
    }
    if (signsPackages && installerIdentity == null) {
      installerIdentity = sha1;
    }
  }

  /** Waits for the identities the targets need; the CryptoTokenKit takes a while to publish. */
  private void awaitIdentities(ProcessRunner runner, Map<Path, Kind> targets, boolean viaSignPath)
      throws IOException, InterruptedException, ToolException {
    codesignIdentity = identity;
    productsignIdentity = installerIdentity;
    boolean signsCode =
        !AD_HOC_IDENTITY.equals(identity)
            && targets.values().stream().anyMatch(kind -> kind != Kind.INSTALLER_PACKAGE);
    boolean signsPackages = targets.containsValue(Kind.INSTALLER_PACKAGE);
    List<String> wanted = new ArrayList<>();
    if (signsCode) {
      wanted.add(identity);
    }
    if (signsPackages) {
      wanted.add(installerIdentity);
    }
    if (wanted.isEmpty()) {
      return;
    }
    Duration timeout =
        viaSignPath ? Duration.ofSeconds(Math.max(1, identityTimeout)) : Duration.ZERO;
    Map<String, KeychainIdentities.Identity> found =
        KeychainIdentities.await(runner, pauser(), wanted, timeout, this::verboseOut);
    for (KeychainIdentities.Identity selected : found.values()) {
      verboseOut("Signing identity: " + selected.describe());
      if (selected.untrusted()) {
        err(
            "Warning: the certificate of signing identity "
                + selected.describe()
                + " is not trusted on this machine. codesign signs with it, but Gatekeeper and"
                + " notarization reject the result: use it for testing only.");
      }
    }
    if (signsCode) {
      codesignIdentity = found.get(identity).sha1();
    }
    if (signsPackages) {
      productsignIdentity = found.get(installerIdentity).name();
    }
  }

  /** Copies {@code input} to the output directory, if any, and returns the path to sign. */
  private Path prepareOutput(ProcessRunner runner, Path input)
      throws IOException, InterruptedException, ToolException {
    if (outputDir == null) {
      return input;
    }
    Path dir = outputDir.toAbsolutePath().normalize();
    Path destination = dir.resolve(input.getFileName());
    if (destination.equals(input)) {
      return input; // --force-overwrite was checked: in place
    }
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
      if (!forceOverwrite) {
        throw new ParameterException(
            spec.commandLine(),
            destination + " already exists. Use --force-overwrite to replace it.");
      }
      deleteRecursively(destination);
    }
    Files.createDirectories(dir);
    // ditto keeps the symbolic links, permissions and extended attributes of bundles.
    runner.runChecked(List.of("ditto", input.toString(), destination.toString()));
    return destination;
  }

  private void sign(ProcessRunner runner, Path target, Kind kind)
      throws IOException, InterruptedException, ToolException {
    switch (kind) {
      case BUNDLE -> {
        List<Path> order = MacOsCodeScanner.signingOrder(target);
        Path top = order.get(order.size() - 1);
        for (Path code : order) {
          runner.runChecked(codesignCode(code, code.equals(top)));
        }
        verifyCode(runner, target, true);
      }
      case MACH_O -> {
        runner.runChecked(codesignCode(target, true));
        verifyCode(runner, target, false);
      }
      case DISK_IMAGE -> {
        List<String> command = codesignBase();
        command.add(target.toString());
        verboseOut(String.join(" ", command));
        runner.runChecked(command);
        verifyCode(runner, target, false);
      }
      case INSTALLER_PACKAGE -> signInstallerPackage(runner, target);
    }
  }

  private List<String> codesignBase() {
    List<String> command = new ArrayList<>(List.of("codesign", "--force"));
    // An ad-hoc signature has no certificate, so it cannot be timestamped.
    command.add(AD_HOC_IDENTITY.equals(identity) ? "--timestamp=none" : "--timestamp");
    command.add("--sign");
    command.add(codesignIdentity);
    return command;
  }

  private List<String> codesignCode(Path code, boolean topLevel) {
    List<String> command = codesignBase();
    command.add("--options");
    command.add("runtime");
    if (topLevel && entitlements != null) {
      command.add("--entitlements");
      command.add(entitlements.toAbsolutePath().normalize().toString());
    }
    command.add(code.toString());
    verboseOut(String.join(" ", command));
    return command;
  }

  private void verifyCode(ProcessRunner runner, Path target, boolean deep)
      throws IOException, InterruptedException, ToolException {
    List<String> command = new ArrayList<>(List.of("codesign", "--verify", "--strict"));
    if (deep) {
      command.add("--deep");
    }
    command.add("--verbose=2");
    command.add(target.toString());
    ProcessRunner.Result result = runner.run(command);
    if (result.exitCode() != 0) {
      throw new ToolException(
          "Signature verification failed for " + target + ProcessRunner.quote(result.output()));
    }
  }

  private void signInstallerPackage(ProcessRunner runner, Path target)
      throws IOException, InterruptedException, ToolException {
    // productsign writes a new file; replace the package once it is complete.
    Path signed = target.resolveSibling("." + target.getFileName() + ".signed");
    Files.deleteIfExists(signed);
    List<String> command =
        List.of(
            "productsign",
            "--timestamp",
            "--sign",
            productsignIdentity,
            target.toString(),
            signed.toString());
    verboseOut(String.join(" ", command));
    try {
      runner.runChecked(command);
      Files.move(
          signed, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(signed);
    }
    ProcessRunner.Result result =
        runner.run(List.of("pkgutil", "--check-signature", target.toString()));
    if (result.exitCode() != 0) {
      throw new ToolException(
          "Signature verification failed for " + target + ProcessRunner.quote(result.output()));
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  private void out(String message) {
    spec.commandLine().getOut().println(message);
    spec.commandLine().getOut().flush();
  }

  private void err(String message) {
    spec.commandLine().getErr().println(message);
    spec.commandLine().getErr().flush();
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

  /** Waits between two keychain lookups. Visible for testing. */
  KeychainIdentities.Pauser pauser() {
    return duration -> Thread.sleep(duration.toMillis());
  }

  /** Fetches the signing policy's certificate from SignPath. Visible for testing. */
  byte[] signingPolicyCertificate(SignPathTokenSession.Settings settings)
      throws IOException, ToolException {
    return SignPathTokenSession.fetchPolicyCertificate(settings);
  }

  /** Creates the CryptoTokenKit session. Visible for testing. */
  SignPathTokenSession newTokenSession(
      ProcessRunner runner, SignPathTokenSession.Settings settings) {
    Consumer<String> log = this::out;
    return new SignPathTokenSession(
        runner,
        SignPathTokenSession::httpsDownload,
        log,
        settings,
        SignPathTokenSession.defaultLogDirectory());
  }

  /** Resolves the API token, as {@code sign} does. Visible for testing. */
  String resolveToken() {
    Path effectiveConfigFile = configFile != null ? configFile : TokenResolver.DEFAULT_CONFIG_FILE;
    return TokenResolver.resolve(
        System.getenv(TokenResolver.ENV_VAR),
        effectiveConfigFile,
        msg -> spec.commandLine().getErr().println("[WARNING] " + msg));
  }
}
