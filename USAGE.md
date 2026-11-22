# Usage Guide

This guide covers both tools in the Eclipse CSI Codesign project:

- **Maven Plugin** (`codesign-maven-plugin`) — integrates artifact signing into Maven builds
- **CLI** (`csi-codesign`) — standalone binary for signing in any pipeline or shell

> For runnable examples of each way to sign (CLI scripts, Maven projects, GitHub Actions
> workflows and the matching SignPath artifact configurations), see [demo/](demo/).
>
> For contributor and developer documentation, see [CONTRIBUTING.md](CONTRIBUTING.md).

---

## Installation

### Maven Plugin

Add the plugin to your `pom.xml`. No additional installation is required — Maven resolves it
from Maven Central.

**Minimum requirements:** Java 21, Maven 3.10.

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.eclipse.csi</groupId>
      <artifactId>codesign-maven-plugin</artifactId>
      <version>VERSION</version>
    </plugin>
  </plugins>
</build>
```

Replace `VERSION` with the latest release from
[Maven Central](https://central.sonatype.com/artifact/org.eclipse.csi/codesign-maven-plugin).

### CLI

Download the pre-built native binary archive for your platform from the
[GitHub Releases](https://github.com/eclipse-csi/codesign-tools/releases) page.
Each archive contains a single `csi-codesign` executable (`csi-codesign.exe` on Windows).

| Platform | Archive name |
| --- | --- |
| Linux x86\_64 | `csi-codesign-<version>-linux-x86_64.tar.gz` |
| Linux aarch64 | `csi-codesign-<version>-linux-aarch_64.tar.gz` |
| macOS Apple Silicon | `csi-codesign-<version>-osx-aarch_64.tar.gz` |
| Windows x86\_64 | `csi-codesign-<version>-windows-x86_64.zip` |

```bash
# Linux / macOS: extract and place on PATH
tar -xzf csi-codesign-<version>-linux-x86_64.tar.gz
sudo mv csi-codesign /usr/local/bin/csi-codesign
# Adapt the archive name for your platform (see table above)
```

**JVM alternative:** if you prefer running on the JVM, the `-bin` fat JAR is published to Maven
Central alongside the native binaries:

```bash
java -jar codesign-cli-<version>-bin.jar sign --help
```

---

## Authentication

Both tools authenticate against the SignPath REST API using a Bearer API token.

### Maven Plugin Authentication

The plugin resolves the token in this order (first non-blank value wins):

1. **Maven `settings.xml`** — password of the server entry whose `<id>` matches `codesign` (or
   the value of the `<serverId>` parameter):

   ```xml
   <!-- ~/.m2/settings.xml -->
   <servers>
     <server>
       <id>codesign</id>
       <password>YOUR_API_TOKEN</password>
     </server>
   </servers>
   ```

   Passwords encrypted with `mvn --encrypt-password` are supported.

2. **Environment variable** — `CSI_CODESIGN_API_TOKEN`.

3. **Config file** — `api.token` key in `~/.config/eclipse-csi-codesign/config.properties`
   (or the path specified by `<configFile>` / `-Dcsi.codesign.configFile`). The file must
   not be readable by other users (POSIX mode `600` or equivalent Windows ACL). A warning
   is emitted if the file has insecure permissions.

If none are set, the build fails with an error listing all three options.

> **Security note:** Prefer `settings.xml` or the environment variable over the config file
> for CI/CD environments. Never hard-code the token in the POM.

### CLI Authentication

The CLI resolves the token in this order (first non-blank value wins):

1. **`CSI_CODESIGN_API_TOKEN`** environment variable.
2. **Config file** — `api.token` key in `~/.config/eclipse-csi-codesign/config.properties`
   (or the path specified by `--config-file`):

   ```properties
   api.token=YOUR_API_TOKEN_HERE
   ```

If none are set, the CLI prints a descriptive error and exits with code 2.

> **Security note:** Prefer the environment variable or config file to avoid token exposure.

---

## Quickstart

### CLI: Sign a file

```bash
export CSI_CODESIGN_API_TOKEN=your-token-here

csi-codesign sign app.jar \
  --organization-id my-org \
  --project-id my-product \
  --signing-policy release-signing \
  --output app-signed.jar
```

The original `app.jar` is unchanged; `app-signed.jar` contains the signed binary.

### Plugin: Ad-hoc invocation

Run signing from the command line without modifying your `pom.xml`:

```shell
mvn org.eclipse.csi:codesign-maven-plugin:codesign \
  -Dcsi.codesign.organizationId=my-org \
  -Dcsi.codesign.projectId=my-product \
  -Dcsi.codesign.signingPolicy=release-signing
```

The main artifact of the current project is submitted for signing and downloaded in place
(assuming standard `jar`/`war`/etc. packaging; see [File Selection](#file-selection-plugin)).

### Plugin: Embedded in the build lifecycle

Configure the plugin in `pom.xml` to sign files automatically during `package`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.eclipse.csi</groupId>
      <artifactId>codesign-maven-plugin</artifactId>
      <version>VERSION</version>
      <executions>
        <execution>
          <id>sign-binaries</id>
          <phase>package</phase>
          <goals>
            <goal>codesign</goal>
          </goals>
          <configuration>
            <!-- Required SignPath coordinates -->
            <organizationId>${env.SIGNPATH_ORG_ID}</organizationId>
            <projectId>my-product</projectId>
            <signingPolicy>release-signing</signingPolicy>
            <!-- artifactConfiguration is optional. Several files are signed as one zip,
                 so this one must have a <zip-file> root element -->
            <artifactConfiguration>binaries-in-zip</artifactConfiguration>

            <!-- Optional metadata -->
            <description>${project.artifactId} ${project.version}</description>
            <parameters>
              <buildNumber>${env.BUILD_NUMBER}</buildNumber>
              <gitCommit>${env.GIT_COMMIT}</gitCommit>
            </parameters>

            <!-- Select files from ${project.build.directory} -->
            <includes>
              <include>*.jar</include>
              <include>*.exe</include>
              <include>*.dmg</include>
            </includes>
            <excludes>
              <exclude>*-sources.jar</exclude>
              <exclude>*-javadoc.jar</exclude>
            </excludes>

            <!-- Write signed files to a separate folder (omit to replace in place) -->
            <outputDirectory>${project.build.directory}/signed</outputDirectory>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

After `mvn package`, signed files are available in `target/signed/`. All the files of the
execution are signed in one signing request, packed in a zip file when there are several; see
[Signing Requests](#signing-requests).

> **Parameters must be declared in the artifact configuration.** Values passed with
> `<parameters>` (plugin) or `--param key=value` (CLI) are only kept by SignPath when the
> artifact configuration declares a parameter of that name in its `<parameters>` block, e.g.
> `<parameter name="buildNumber" />`. SignPath silently drops undeclared parameters, and
> rejects the request (HTTP 400) when a parameter declared with `required="true"` is missing.
> See [User-defined parameters](https://docs.signpath.io/artifact-configuration/syntax#user-defined-parameters).

### GitHub Actions: Trusted Build System

A SignPath signing policy with **trusted build system verification** (as used by Eclipse
Foundation projects) rejects files uploaded directly to the REST API. SignPath has to fetch
the file from GitHub itself, so that it can verify which repository, workflow and branch
built it. Select this flow with `--submission-mode github-actions` (CLI) or
`<submissionMode>github-actions</submissionMode>` (plugin).

> **Signing files outside of a Maven build?** Use the
> [`eclipse-csi/codesign-action`](https://github.com/eclipse-csi/codesign-action) GitHub Action.
> In one step it downloads and verifies `csi-codesign`, hands it the job's credentials, and signs
> the files in this mode. Nothing below needs to be set up by hand.

Here is what it does:

1. The files of one invocation (CLI) or one execution (plugin) are stored as an artifact of the
   running GitHub Actions job, kept for one day:
   - **A single file** is uploaded as is, like `actions/upload-artifact` with `archive: false`.
   - **Several files** are packed at the root of a zip file, like `actions/upload-artifact` does
     by default.
2. The artifact is submitted in **one** signing request through the
   [SignPath GitHub connector](https://docs.signpath.io/trusted-build-systems/github).
3. The tool polls the request through the REST API. Once it completes, it downloads the signed
   artifact and writes each signed file to its output location, as in direct mode.

Requirements:

- **Artifact configuration.** SignPath receives the same thing as in direct mode, so the same
  artifact configuration applies: the file itself, or a zip when several files are signed
  together (see [Signing Requests](#signing-requests)).
- **Unique file names within a workflow run.** GitHub names a single-file artifact after the
  file, and artifact names must be unique within a workflow run. So within one run, a given
  file name can only be signed on its own once, even across jobs (e.g. a matrix that signs
  `app.exe` on several runners). Name the files differently, or sign them together.
- **Environment.** The signing step needs these variables:
  - `GITHUB_RUN_ID` and `GITHUB_REPOSITORY`, which GitHub sets for every step.
  - `GITHUB_TOKEN`, passed explicitly. SignPath uses it to read the workflow run and download
    the artifact, so the job needs the `actions: read` permission.
  - `ACTIONS_RESULTS_URL` and `ACTIONS_RUNTIME_TOKEN`. GitHub only gives these to JavaScript
    actions, never to `run:` steps. The `expose-actions-runtime` action of this repository
    copies them to step outputs. Hand them only to the signing step, because the runtime token
    can upload artifacts and caches for the job.
- **Unsupported options.** `--description` / `<description>` is ignored, because the connector
  does not accept it.

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      actions: read   # SignPath downloads the workflow artifact with GITHUB_TOKEN
      contents: read
    steps:
      # ... check out and build ...
      - id: actions-runtime
        uses: eclipse-csi/codesign-tools/.github/actions/expose-actions-runtime@<commit-sha>
      - name: Sign
        env:
          CSI_CODESIGN_API_TOKEN: ${{ secrets.SIGNPATH_API_TOKEN }}
          GITHUB_TOKEN: ${{ github.token }}
          ACTIONS_RESULTS_URL: ${{ steps.actions-runtime.outputs.actions-results-url }}
          ACTIONS_RUNTIME_TOKEN: ${{ steps.actions-runtime.outputs.actions-runtime-token }}
        run: |
          csi-codesign sign --submission-mode github-actions \
            --organization-id <ORG_ID> --project-id <PROJECT_SLUG> \
            --signing-policy <POLICY_SLUG> --artifact-configuration <CONFIG_SLUG> \
            --output-dir signed/ target/app.jar
```

With the Maven plugin, set `<submissionMode>github-actions</submissionMode>` and run Maven
in a step that has the same `env:` block. If SignPath rejects the request, for example because
the branch is not allowed by the policy's origin verification, the error lists each policy
violation together with SignPath's hint on how to fix it.

### macOS: Sign and Notarize

SignPath has no artifact configuration for Apple formats. Apps, Mach-O binaries, disk images
and installer packages are signed on a macOS machine by `codesign` and `productsign`, through
the [SignPath macOS CryptoTokenKit](https://docs.signpath.io/crypto-providers/macos). The
CryptoTokenKit publishes the certificate of a signing policy as a signing identity of a smart
card token, and SignPath signs each hash the tools send. `csi-codesign macos sign` runs the whole sequence:

1. It downloads `SignPathCryptoTokenKit.dmg` and checks that it carries a valid Developer ID
   signature of SignPath GmbH (Team ID `PDP2N2TC2T`). With `--ctk-sha256`, it also checks the
   download's SHA-256.
2. It mounts the disk image read-only and starts the CryptoTokenKit for your project and
   signing policy. The API token reaches the CryptoTokenKit in a configuration file that only
   you can read, never on a command line, and the file is deleted afterwards.
3. It waits until the certificate shows up in the token (`--identity-timeout`, default 60 s).
   `security find-identity` does not list token identities: `security export-smartcard -t
   identities` and `system_profiler SPSmartCardsDataType` do.
4. It signs each input:
   - **Bundles** (`.app`, `.framework`, …) from the inside out: every nested Mach-O binary and
     bundle comes before the bundle that contains it, with the hardened runtime and a secure
     timestamp, as notarization requires. `--entitlements` applies to the top-level bundle
     only.
   - **Mach-O binaries** the same way.
   - **Disk images** with a timestamp only.
   - **`.pkg` files** with `productsign`.

   SignPath signs each hash while `codesign` or `productsign` waits. When SignPath refuses,
   the CryptoTokenKit logs the reason but does not tell the tool, which would wait for ever:
   `macos sign` stops it as soon as the refusal appears in the CryptoTokenKit logs, and reports
   SignPath's reason. Each call is also stopped after `--signing-timeout` (default 300 s).
5. It verifies every signature (`codesign --verify --strict`, `pkgutil --check-signature`).
6. It stops the CryptoTokenKit and unmounts the disk image, including on failure.

```bash
export CSI_CODESIGN_API_TOKEN=...
csi-codesign macos sign build/MyApp.app \
  --organization-id <ORG_ID> --project-id <PROJECT_SLUG> --signing-policy <POLICY_SLUG> \
  --entitlements MyApp.entitlements \
  --output-dir signed/

hdiutil create -format UDZO -srcfolder signed/MyApp.app signed/MyApp.dmg
csi-codesign macos sign signed/MyApp.dmg --force-overwrite \
  --organization-id <ORG_ID> --project-id <PROJECT_SLUG> --signing-policy <POLICY_SLUG>
```

Without `--identity` (or `--installer-identity` for `.pkg` files), `macos sign` signs with the
certificate of the signing policy: it looks the certificate up with the SignPath API before
starting the CryptoTokenKit, and selects it by its SHA-1 hash.

Requirements on the SignPath side:

- The project's **default** artifact configuration must be of type *hash signing data*, because
  the CryptoTokenKit has no setting to choose another one. File-based configurations can live in
  the same project: `csi-codesign sign` selects them with `--artifact-configuration`.
- The signing policy's certificate is a Developer ID Application certificate. A policy for
  testing can use a self-signed SignPath certificate with the *Code Signing* extended key
  usage. macOS does not trust it, so `macos sign` prints a warning and signs anyway; Gatekeeper
  and notarization reject such signatures.
- A policy has a single certificate, so `.pkg` installers (Developer ID Installer) are signed
  in a separate invocation, with their own policy and `--installer-identity`.
- SignPath refuses hash signing under policies with **trusted build system
  verification**, and does not offer policies with origin verification to the CryptoTokenKit at
  all: the policy must have neither. With trusted build system verification, `macos sign` fails
  with SignPath's 403 "The selected signing policy requires that the signing request is
  submitted via a trusted build system."

`--identity` selects the certificate the way `codesign -s` does: by its SHA-1 hash, or by a part
of its name, preferring an exact match. Certificates that cannot sign, for example expired ones,
are ignored. `codesign` then receives the SHA-1 hash of the certificate found, so an older
certificate with the same name in the keychain does not make the choice ambiguous. A self-signed
certificate in the token counts as untrusted.

`--token-provider keychain` skips the CryptoTokenKit and uses identities already in the
keychain, e.g. a local certificate, or `--identity -` for an ad-hoc signature when testing.

**Notarization** is a separate step. It submits each input to Apple's notary service and waits
for the verdict. If Apple rejects a submission, the error lists each issue from the
notarization log. Otherwise the ticket is stapled (`--no-staple` skips this) and Gatekeeper
(`spctl --assess`) must accept the result. An app is zipped for submission, and the app itself
is stapled. A Mach-O binary, such as a command-line tool, is zipped for submission too, but
Apple cannot staple a ticket to it, and Gatekeeper does not assess it: it looks the ticket up
online when the binary first runs.

```bash
export CSI_CODESIGN_NOTARY_KEY="$(cat AuthKey_ABC123DEFG.p8)"   # or --notary-key <file>
export CSI_CODESIGN_NOTARY_KEY_ID=ABC123DEFG
export CSI_CODESIGN_NOTARY_ISSUER=69a6de7e-0000-47e3-e053-5b8c7c11a4d1   # team keys only
csi-codesign macos notarize signed/MyApp.dmg
```

It authenticates with an App Store Connect API key, or with a `notarytool` keychain profile
(`--keychain-profile`). Apple ID passwords are not supported. Both commands run on macOS only;
the native binary is released for Apple Silicon (see [Installation](#cli)).

---

## Configuration Reference

Reference documentation is published on the [project site](https://eclipse-csi.github.io/codesign-tools/):

- **Maven Plugin parameters**: [`codesign:codesign` goal reference](https://eclipse-csi.github.io/codesign-tools/latest/maven-plugin/codesign-mojo.html)
- **CLI options**: [CLI man page](https://eclipse-csi.github.io/codesign-tools/latest/cli-manpage/)
- **Java API**: [API Javadoc](https://eclipse-csi.github.io/codesign-tools/latest/api-javadoc/)

> Documentation for past releases and for snapshot (built from the `main` branch) are
> [available](https://eclipse-csi.github.io/codesign-tools/).

### Environment Variables

| Variable | Tool | Description |
| --- | --- | --- |
| `CSI_CODESIGN_API_TOKEN` | Both | SignPath API token. Lower priority than `settings.xml` (plugin); higher priority than the config file. |
| `CSI_CODESIGN_SKIP_SIGNING` | Plugin only | Set to `1`, `true`, or `yes` (case-insensitive) to skip signing unconditionally. |
| `ACTIONS_RESULTS_URL`, `ACTIONS_RUNTIME_TOKEN`, `GITHUB_RUN_ID`, `GITHUB_REPOSITORY`, `GITHUB_TOKEN` | Both | Required by the `github-actions` submission mode; see [GitHub Actions: Trusted Build System](#github-actions-trusted-build-system). |
| `CSI_CODESIGN_NOTARY_KEY` | CLI only | `macos notarize`: content of the App Store Connect API private key (`.p8`). Ignored when `--notary-key` is given. |
| `CSI_CODESIGN_NOTARY_KEY_ID`, `CSI_CODESIGN_NOTARY_ISSUER` | CLI only | `macos notarize`: key ID and issuer ID of the App Store Connect API key. Ignored when `--notary-key-id` or `--notary-issuer` is given. |

---

## Behavior and Guarantees

### File Selection (Plugin)

Two independent mechanisms are combined; their results are merged and deduplicated before
signing:

1. **File scan** — walks `baseDirectory` recursively, matching files against `<includes>` and
   `<excludes>` glob patterns. Patterns are matched against paths relative to `baseDirectory`.
   The scan only runs when `<includes>` contains at least one pattern; `<excludes>` has no
   effect when `<includes>` is empty.

2. **Maven artifact signing** — controlled by `signProjectArtifact` and `signAttachedArtifacts`:
   - `signProjectArtifact=auto` (default): signs the main artifact for `jar`, `war`, `ear`,
     `rar`, `ejb`, and `maven-plugin` packaging; **skips it for `pom` packaging and all other
     unrecognised types**.
   - `signProjectArtifact=true`: always includes the main artifact.
   - `signProjectArtifact=false`: never includes the main artifact.
   - `signAttachedArtifacts=false` (default): attached artifacts are **not** signed.
   - `signAttachedArtifacts=true`: all attached artifacts are included.

When the combined result is empty, the plugin logs `[WARNING] No files selected for signing`
and exits without calling the SignPath API. Set `<failOnNoFilesFound>true</failOnNoFilesFound>`
to turn this into a build failure instead.

### Signing Requests

The files of one invocation (CLI) or one execution (plugin) are signed in **one** signing
request, whatever the submission mode:

- **A single file** is sent as is. The artifact configuration describes the file itself, e.g.
  with a `<jar-file>` or `<pe-file>` root element.
- **Several files** are packed at the root of a zip file. The artifact configuration must have
  a `<zip-file>` root element with rules that match files at the zip root, and the files must
  have distinct names (e.g. not `win/app.exe` and `mac/app.exe` together). For example:

  ```xml
  <artifact-configuration xmlns="http://signpath.io/artifact-configuration/v1">
    <zip-file>
      <jar-file path="*.jar"><jar-sign /></jar-file>
      <pe-file path="*.exe"><authenticode-sign /></pe-file>
    </zip-file>
  </artifact-configuration>
  ```

A signing policy that requires approval therefore asks for one approval per invocation or
execution, not one per file. To sign files in separate requests, e.g. with different artifact
configurations or signing policies, invoke the CLI once per file, or give the plugin one
`<execution>` per set of files.

What SignPath receives depends on how many files are selected: an `<includes>` pattern or
shell glob that starts matching a second file turns a bare file into a zip. If SignPath
rejects the request because the artifact does not match the artifact configuration, check the
log: the tool logs `Packed N files at the root of a zip file` when it zips.

Nothing is written unless the request completes and the signed artifact holds every file.

### Output Modes

**Maven Plugin:**

- `<outputDirectory>` set → each signed file is written to `<outputDirectory>/<filename>`.
- `<outputDirectory>` not set → each signed file replaces the original in place.

**CLI:**

| Condition | Output location |
| --- | --- |
| `--output <path>` (single input file) | Written to the specified path |
| `--output-dir <dir>` (any number of files) | `<dir>/<original-filename>` for each file |
| Neither `--output` nor `--output-dir`, with `--force-overwrite` | In-place replacement (same directory, atomic rename) |

In all cases (plugin and CLI), the signed artifact is downloaded to a temporary `.codesign-*`
directory next to the first output, and each signed file is then written to a `.codesign-tmp`
temporary file in the same directory as its destination and moved atomically into place. On
platforms where atomic rename is unsupported, the tools fall back to a non-atomic move.

### Retry and Timeout

**Maven Plugin** — configured explicitly:

| Parameter | Default | Description |
| --- | --- | --- |
| `pollInterval` | 5 s | Delay between signing-status poll requests |
| `retryTimeout` | 600 s | Maximum time window for HTTP retries |
| `retryInterval` | 30 s | Delay between HTTP retry attempts |
| `maxRetries` | 10 | Maximum number of HTTP retry attempts |
| `httpTimeout` | 300 s | HTTP read/write timeout |
| `connectTimeout` | 30 s | HTTP connect timeout |

**CLI** — all derived from `--wait-for-completion-timeout` (default: 600 s), which applies to
the signing request (one per invocation, see [Signing Requests](#signing-requests)), starting
when it is submitted:

| Parameter | Formula | Example (600 s) |
| --- | --- | --- |
| Poll interval | `clamp(timeout/20, 2 s, 30 s)` | 30 s |
| HTTP read/write timeout | `clamp(timeout/10, 10 s, 60 s)` | 60 s |
| Connect timeout | `min(30 s, timeout)` | 30 s |
| Retry timeout | `max(10 s, timeout/4)` | 150 s |
| Retry interval | `clamp(timeout/20, 2 s, 30 s)` | 30 s |
| Max retries | fixed | 5 |

Use `--verbose` to see the derived values for your chosen timeout.

HTTP retries are triggered by status codes 429, 502, 503, 504, and connection/read
timeouts. Retries stop when either the retry count or the retry time window is exhausted,
whichever comes first.

### Signing Failure Modes

#### Plugin — build errors (`MojoExecutionException`; build aborts immediately)

| Condition | Cause |
| --- | --- |
| No API token found | None of the three token sources is configured |
| HTTP error on submit | Non-201 response after all retries exhausted |
| Missing `Location` header | API returned HTTP 201 without a `Location` header |
| HTTP error on status poll | Non-2xx response after all retries exhausted |
| HTTP error on download | Non-2xx response when downloading the signed artifact |
| No signed artifact link | Status is `Completed` but `signedArtifactLink` is `null` |
| I/O error writing signed file | Disk/filesystem error during temp-file write or atomic rename |
| Directory scan error | I/O error while walking `baseDirectory` |
| Poll interrupted | Thread interrupted during the sleep between status polls |

#### Plugin — build failures (`MojoFailureException`; signing processed but unsuccessful)

| Status | Typical reason |
| --- | --- |
| `Failed` | Policy validation error or SignPath internal error |
| `Denied` | A required approval step was rejected by a reviewer |
| `Canceled` | Signing request was canceled before completion |
| No files found (`failOnNoFilesFound=true`) | No files matched the configured selection |

#### CLI — exit codes

| Code | Meaning |
| --- | --- |
| `0` | All files signed successfully |
| `1` | Execution error (API failure, signing denied/failed/canceled, I/O error) |
| `2` | Bad arguments (missing required option, incompatible flags) |

---

## Troubleshooting

**"No API token found"**
No token source is configured. Add a `codesign` server entry to `~/.m2/settings.xml`, set
`CSI_CODESIGN_API_TOKEN` in the environment, or store the token in
`~/.config/eclipse-csi-codesign/config.properties` (key `api.token`).

**HTTP 401 Unauthorized**
The token is invalid, expired, or does not have permission to submit signing requests for
the specified organization. Verify the token in the SignPath portal. SignPath also answers
401 (`User is not allowed to log into the organization`) when the organization ID is wrong,
and 404 when it is not a valid organization ID at all.

**HTTP 400 "One or more validation errors occurred"**
SignPath rejected the submitted request; the error message names the offending field. The
most common causes are a project, signing policy, or artifact configuration slug that does
not exist (`There is no signing policy with slug '…' in the project with slug '…'`), a
signing policy that the token's user is not a submitter of, or a missing required
user-defined parameter (`The parameters '…' are required`).

**Signing status `Failed`**
SignPath rejected the artifact — usually a policy violation (wrong file type, policy
mismatch) or a SignPath internal error. Check the `workflowStatus` field printed in the
build/CLI output, and open the signing request link printed with the error to see
SignPath's processing log. Review the signing policy and artifact configuration in the
SignPath portal.

**Signing status `Denied`**
A human approver rejected the signing request. This is expected when a policy requires
manual approval and the reviewer denied it.

**Signing status `Canceled`**
The signing request was canceled (e.g. via the SignPath portal) before it completed.

**"No files selected for signing" warning**
Neither the file scan nor the Maven artifact selection produced any candidates.

- Verify that `<includes>` patterns match files actually present in `${project.build.directory}`.
- Check that `signProjectArtifact` is not `false` when you expect the main artifact to be signed.
- For `pom`-packaging projects (aggregator modules), `signProjectArtifact=auto` (the default)
  produces no artifact. Either set `<signProjectArtifact>false</signProjectArtifact>` to
  suppress the warning, or remove the plugin configuration for that module entirely.

**Signing is skipped unexpectedly**
Check whether `CSI_CODESIGN_SKIP_SIGNING` is set to `1`, `true`, or `yes` in the
environment. Also check `<skip>true</skip>` in the POM or `-Dcsi.codesign.skip` on the
command line.

**Timeout waiting for signing completion (CLI)**
Signing a file took longer than `--wait-for-completion-timeout` (measured separately for each
file). Increase it, and check the SignPath portal — the request may be waiting for manual
approval. The signing request is not canceled on timeout: it stays open in SignPath (the
error message links to it), so cancel it there if it is no longer needed. The Maven plugin has no completion
timeout: it polls until SignPath reports a final status (`<retryTimeout>` only bounds retries
of transient HTTP failures).

**"File(s) not found or not regular files" (CLI)**
The files passed as positional arguments do not exist at the given paths. Check paths and
shell glob expansion. On Windows PowerShell, expand globs manually:

```powershell
$files = Get-ChildItem target\*.jar | ForEach-Object { $_.FullName }
csi-codesign sign @files --organization-id ... --output-dir signed\
```

**`--output` rejected when multiple input files are given**
`--output` is only valid for a single input file. Use `--output-dir` when signing multiple
files at once.

**"No output specified … use --force-overwrite" (CLI)**
When neither `--output` nor `--output-dir` is specified, the CLI requires `--force-overwrite`
as an explicit opt-in for in-place replacement. The same applies when `--output` points to
the same path as the input file.
