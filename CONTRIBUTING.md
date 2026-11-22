# Contributing to Eclipse CSI Codesign Maven Plugin

Thanks for your interest in this project.

## Project Description

The Eclipse CSI Codesign Maven Plugin is a Maven plugin that signs
artifacts via the SignPath REST API. It is part of the Eclipse Common
Security Infrastructure (CSI) project.

- <https://projects.eclipse.org/projects/technology.csi>

## Developer Resources

Information regarding source code management, builds, coding standards,
and more.

- <https://projects.eclipse.org/projects/technology.csi/developer>

The project maintains the following source code repository:

- <https://github.com/eclipse-csi/codesign-tools>

## Eclipse Development Process

This Eclipse Foundation open project is governed by the Eclipse Foundation
Development Process and operates under the terms of the Eclipse IP Policy.

- <https://eclipse.org/projects/dev_process>
- <https://www.eclipse.org/org/documents/Eclipse_IP_Policy.pdf>

## Eclipse Contributor Agreement

In order to be able to contribute to Eclipse Foundation projects you
must electronically sign the Eclipse Contributor Agreement (ECA):

- <https://www.eclipse.org/legal/ECA.php>

The ECA provides the Eclipse Foundation with a permanent record that you
agree that each of your contributions will comply with the commitments
documented in the Developer Certificate of Origin (DCO). Having an ECA
on file associated with the email address matching the "Author" field of
your contribution's Git commits fulfills the DCO's requirement that you
sign-off on your contributions.

For more information, please see the Eclipse Committer Handbook:

- <https://www.eclipse.org/projects/handbook/#resources-commit>

## Development Setup

### Prerequisites

Install the following tools **before** running any build or quality check:

| Tool | Version | Required for |
| --- | --- | --- |
| **Java JDK** | 21+ | Maven wrapper (`./mvnw`), `unit-tests` and `integration-tests` git hooks (`language: system`), `pretty-format-java` Java formatter |
| **prek** | any | Running Git hooks (see below) |
| **poutine** | any | `poutine` git hook — workflow security analysis |

`cdxgen` and `cyclonedx-cli` are additionally needed for the `generate-sbom`
profile, and `trivy` to cut a release; see [Additional Development Tools](#additional-development-tools) below.

This project uses [prek](https://prek.j178.dev/) to run quality checks
automatically as Git hooks. prek is written in Rust and does not require
Python to install itself — but the hooks it drives require a Java JDK on
the `PATH` (for Maven builds and the Java formatter) and the `poutine`
binary for workflow security analysis.

The hook config lives in `prek.toml`.

### Installing prek

Install prek using your preferred method:

| Platform | Command |
| --- | --- |
| Homebrew (macOS/Linux) | `brew install prek` |
| uv | `uv tool install prek` |
| pip / pipx | `pip install prek` |
| Scoop (Windows) | `scoop install main/prek` |

See the [prek installation docs](https://prek.j178.dev/installation/) for
all options (Nix, conda, cargo, Docker, …).

### Installing the Git hooks

Once prek is installed, wire it into the repository's Git hooks:

```sh
prek install
```

This installs two hooks driven by `prek.toml`:

- **commit-time hook** — runs:
  - [zizmor](https://github.com/woodruffw/zizmor) (GitHub Actions security linter)
  - [poutine](https://github.com/boostsecurityio/poutine) (Build pipeline security analyzer)
  - `check-merge-conflict`, `check-yaml`, `check-added-large-files`,
    `detect-private-key`, and line-ending/whitespace hygiene checks
  - `actionlint` and YAML validation for GitHub workflows
  - `ruff`/`ruff-format` for Python scripts under `.github/scripts`
  - `google-java-format` (via `pretty-format-java`) for Java sources under `src/`
  - `markdownlint-cli` for Markdown files (configured via `.markdownlint.yaml`)
  - the local POM consistency check
  on every `git commit`.
- **pre-push** — runs Maven unit tests and integration tests on every `git push`.

### Additional Development Tools

Some quality checks and build profiles require tools to be installed on your
system:

| Tool | Purpose | Installation |
| --- | --- | --- |
| **poutine** | Workflow security analysis (run by the commit-time hook) | `brew install boostsecurityio/tap/poutine` |
| **cdxgen** | GitHub Actions SBOM generation (used in `generate-sbom` profile) | Requires Node.js; `npm install -g @cyclonedx/cdxgen` |
| **cyclonedx-cli** | SBOM merging (used in `generate-sbom` profile) | `brew install cyclonedx-cli` |
| **trivy** | SBOM vulnerability scan run by `release.sh` (only needed to cut a release) | `brew install trivy` |
| **GraalVM** | Native CLI binary builds (optional) | [graalvm.org](https://www.graalvm.org/) |

### Running the Python scripts manually

The scripts under `.github/scripts/` require `defusedxml`. Use `uv` to run
them without a permanent virtual environment:

```sh
uv run --with defusedxml python3 .github/scripts/check-pom-consistency.py
uv run --with defusedxml python3 .github/scripts/generate-site-index.py
```

### Pull request checks

CI also runs the same hook checks on pull requests. In addition,
reviewdog posts granular inline comments/suggestions for workflow, Python,
and Markdown lint findings.

Hook/tool revisions are updated automatically by
`.github/workflows/maintenance-update-tool-versions.yml`, which also runs
`prek update --freeze` to keep hook revisions immutable.

## Building

```shell
# Default full build (unit tests only)
./mvnw clean verify

# Full build with integration tests
./mvnw clean verify -Pintegration-tests

# Build the CLI JVM fat JAR only
./mvnw package -pl cli --also-make

# Build a native CLI binary (requires GraalVM with native-image)
./mvnw -Pnative package -pl cli --also-make -DskipTests
# Output: cli/target/csi-codesign  (or csi-codesign.exe on Windows)
```

To generate SBOMs (CycloneDX) in a build, enable the `generate-sbom` profile
(requires `cdxgen` and `cyclonedx-cli`; see [Additional Development Tools](#additional-development-tools)):

```shell
./mvnw clean verify -Pgenerate-sbom
```

### Project version

The version is not written in the POMs: their `<version>0.0.0-SNAPSHOT</version>` is a
placeholder. The [Maven Git Versioning Extension](https://github.com/qoomon/maven-git-versioning-extension),
loaded from `.mvn/extensions.xml` and configured in `.mvn/maven-git-versioning-extension.xml`,
derives it from git at build time:

| HEAD | Version |
| --- | --- |
| tagged `vX.Y.Z` | `X.Y.Z` |
| anything else | next patch after the closest `vX.Y.Z` tag, e.g. `0.8.5-SNAPSHOT` after `v0.8.4` |

```shell
./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version
```

Consequences for local work:

- The build needs the git history and tags. In a shallow clone with no reachable release tag,
  the build fails; run `git fetch --unshallow --tags`.
- `-Dversioning.disable=true` turns the extension off and builds the `0.0.0-SNAPSHOT`
  placeholder. IntelliJ IDEA users may want to add it to the Maven importer's VM options.
- The extension writes a `.git-versioned-pom.xml` next to each `pom.xml` (git-ignored); it is
  the POM that gets installed and deployed.
- `codesign --version` reports the `codesign.build.version` property instead of the Maven
  version: the release version on a tag, and `X.Y.Z-dev<N>+g<commit>` otherwise (e.g.
  `0.8.5-dev18+g1a2b3c4`, 18 commits after `v0.8.4`), so a `-SNAPSHOT` binary can be traced
  back to its commit.

In CI, every checkout that runs Maven uses `fetch-depth: 0`. GitHub's automatic dependency
submission checks out a shallow clone that cannot be configured, so it must stay disabled
for this repository (Settings > Advanced Security > Dependency graph);
`.github/workflows/dependency-submission.yml` submits the Maven dependency graph instead.

## Releasing

Releases are published by `.github/workflows/release.yml` when a `vX.Y.Z` tag is pushed,
normally by `release.sh` (see [Cutting a Release](#cutting-a-release)). In order, it publishes
to:

1. **Maven Central**: `codesign-api`, `codesign-maven-plugin` and `codesign-cli` (with the
   `-bin` fat JAR), built with the `release`, `generate-sbom` and `codesign` profiles and
   deployed by JReleaser. The JARs (not the sources and javadoc JARs) are signed with SignPath
   (see [Code Signing in CI](#code-signing-in-ci)). Every file has an OpenPGP signature (`.asc`) and a Sigstore bundle
   (`.sigstore.json`, from `sigstore-maven-plugin`). The JARs, POMs and CycloneDX SBOMs also
   get a build provenance attestation.
2. **GitHub Releases**: the native CLI archives for each platform and the CLI fat JAR (signed
   with SignPath, like the Windows binary in its archive), each
   with an OpenPGP signature, a Sigstore bundle (from `cosign`) and a build provenance
   attestation, plus a signed `checksums_sha256.txt`. The release body is rendered from
   `CHANGELOG.md` (see [Release Notes](#release-notes)).
3. **The documentation site**, last, so that a release that failed never reaches it.

Build provenance attestations are stored by GitHub and checked with `gh attestation verify`;
they are not uploaded as files. Each release's notes show how to verify all of the above.

Nothing is published until everything is built: the `maven-build` and `native-build` jobs run
first, and only then does `maven-central` publish, followed by the GitHub release and the
site.

The workflow can also be run manually (`workflow_dispatch`). `mode=publish` must be dispatched
from a release tag. `mode=dry-run` works from any branch: it runs only the `maven-build` job
(build with integration tests, SBOM vulnerability scan, JReleaser deploy in dry-run mode), with
the Maven Central check relaxed to accept the `-SNAPSHOT` version a branch builds. It does not
build the native binaries or exercise the GitHub release; the [Local Dry Run](#local-dry-run)
covers JReleaser's part of that.

### Recovering from a Failed Release

Use **Re-run failed jobs** on the failed run: it retries only what failed. Workflow artifacts
are kept for 7 days, so this works within that time. After that, or if the run is gone, run
the workflow again for the tag (`workflow_dispatch` from the tag, `mode=publish`); there is no
need to delete or push the tag again.

The `maven-central` job is safe to run any number of times, as a re-run attempt or as part of a
new run for the same tag. It runs `.github/scripts/publish-to-maven-central.py`, which has
JReleaser upload the bundle and wait for validation (its `UPLOAD` stage), then publishes the
deployment through the Central Portal API itself. As soon as the bundle is uploaded, the script
records the deployment ID in a commit status on the release commit (context
`Maven Central (X.Y.Z)`), with the SHA-256 digest of the jars and POMs it was built from. Every
later run for that commit finds it there, even if the job was killed. Then:

- if all the components are already published, it downloads their jars and POMs from Maven
  Central and compares them with the staged ones. Identical, there is nothing to do (for
  example, publication finished after the job gave up). Different, the job fails: Central holds
  something other than what this commit builds, and a version cannot be republished;
- if the recorded deployment was built from the same jars and POMs, it carries on with it
  (waits for validation, publishes it, or waits for its publication) instead of uploading a
  second one for the same version, which Central refuses to publish (HTTP 400). If that
  deployment failed, it drops it and uploads again;
- if the recorded deployment was built from other files, it drops it and uploads again, unless
  Central is already publishing it, in which case the job fails;
- if only some of the components are published, it stops: check the
  [Central Portal deployments](https://central.sonatype.com/publishing/deployments).

Only the jars and POMs are compared, because the build is reproducible: building the same
commit again produces the same POMs, and jars with the same entries. The jars themselves are not
byte for byte the same, because each build signs them again with SignPath, and a signature
carries the signing time. They are therefore compared entry by entry, without their signature
files (`META-INF/*.SF`, `*.RSA`, `*.DSA`, `*.EC`, `SIG-*`). Detached signatures, Sigstore
bundles and SBOMs differ on every build, so a rebuilt staging repository still matches a
deployment made from an earlier build.

The script gives up after 60 minutes, before the job's own timeout, and says where the
deployment stands.

### Cutting a Release

```shell
export GPG_KEY_ID=<artifact signing key>   # see Release Notes below
./release.sh --dry-run 0.8.5               # every check, then prints what it would do
./release.sh 0.8.5
```

Before it changes anything, `release.sh` checks that:

- you are on `main`, with no uncommitted changes to tracked files, and not behind or diverged
  from `origin/main`;
- git can sign the commit and the tag: `user.signingkey` is set and usable with the configured
  `gpg.format` (OpenPGP or SSH);
- the version is `X.Y.Z`, greater than the latest release tag, and not already in
  `CHANGELOG.md`;
- the `[Unreleased]` section of `CHANGELOG.md` exists, has content, and renders as release
  notes (which needs `GPG_KEY_ID`);
- the aggregate SBOM has no fixable HIGH or CRITICAL vulnerability. Like the workflow, the
  script builds it with the `generate-sbom` profile and scans it with Trivy, so `trivy`,
  `cdxgen` and `cyclonedx` must be installed (see
  [Additional Development Tools](#additional-development-tools)).

It then asks for confirmation.

Because the version comes from the tag (see [Project version](#project-version)), the script
makes a single signed commit that only touches `CHANGELOG.md` (the `[Unreleased]` section
becomes `[v0.8.5]`, and a new empty `[Unreleased]` section is opened), tags it `v0.8.5`,
checks that Maven now derives `0.8.5` from the tag, and pushes `main` and the tag. Every later
commit builds as `0.8.6-SNAPSHOT` without any POM change. A minor or major release is the same
command with that version (e.g. `./release.sh 0.9.0`); it only has to be greater than the
latest release tag.

### Required GitHub Secrets

Configure these repository/environment secrets before running the release workflow:

- `CENTRAL_SONATYPE_TOKEN_USERNAME`
- `CENTRAL_SONATYPE_TOKEN_PASSWORD`
- `GPG_PRIVATE_KEY` (ASCII-armored)
- `GPG_KEY_ID`
- `GPG_PASSPHRASE`
- `SIGNPATH_API_TOKEN` (API token of the SignPath CI user, see "Code Signing in CI" below)

### Code Signing in CI

The project signs its own artifacts with SignPath (project `technology.csi`, configured in
[sealform-configuration](https://github.com/EclipseFdn/sealform-configuration/blob/main/ef-projects/projects/technology.csi.yaml)).
All of its signing policies but `untrusted-test-signing` require trusted build system
verification. The tools therefore
submit through the SignPath GitHub connector (`--submission-mode github-actions` for the CLI,
`<submissionMode>github-actions</submissionMode>` for the plugin); a direct REST upload would
be rejected. Each file is uploaded on its own (not zipped), with the `jar-file` and `pe-file`
artifact configurations of the project.

| Workflow | Event | Signed | Policy |
| --- | --- | --- | --- |
| `ci-native.yml` | push to `main`, dispatch | `csi-codesign.exe` (signed by itself, artifact configuration `pe-file`) | `test-signing` |
| `ci-native.yml` | push to `main`, dispatch (unless the macOS signing policy `none` is chosen) | the macOS `csi-codesign` (signed by itself with `macos sign`, through the SignPath macOS CryptoTokenKit) | `untrusted-test-signing`, or `apple-application-release` when chosen |
| `ci.yml` | push to `main`, dispatch | the jars, by the Maven build (`codesign` profile, artifact configuration `jar-file`; not published) | `test-signing` |
| `release.yml` | release tag | the jars deployed to Maven Central, the CLI fat JAR and `csi-codesign.exe` shipped in the Windows archive | `release-signing` |
| `release.yml` | dry run dispatched from a branch | the jars (not published) | `test-signing` |
| any | pull request | nothing (forks and Dependabot cannot read the secret) | — |

The macOS binary cannot go through the GitHub connector: `codesign` sends SignPath the hashes
to sign through the SignPath macOS CryptoTokenKit, under the project's default artifact
configuration, `hash-sign`. SignPath does not offer policies with origin verification for hash
signing, so CI signs it with `untrusted-test-signing`: a self-signed certificate, without
trusted build system verification. Such a signature only proves that the signing round trip
works; it is not trusted by macOS.

The jars are signed inside the Maven build by the `codesign` profile of the root `pom.xml`, in
place, at `pre-integration-test`: after they are packaged (and the CLI fat JAR shaded), before
the Sigstore signatures, checksums, build provenance attestations and OpenPGP signatures are
made, so that these cover the signed jars. The workflows run it as a separate Maven invocation,
after the tests, so that the SignPath token and the job's artifact credentials never reach the
test JVMs. `.github/scripts/check-jars-signed.sh` then checks that every jar is signed.

Maven cannot run a plugin built by the same reactor, so the profile uses the **last released**
`codesign-maven-plugin` (its version is in `<pluginManagement>`), never `${project.version}`.
Dependabot bumps it after each release. The profile only works in a GitHub Actions job: it needs
the environment variables the `expose-actions-runtime` action provides, `GITHUB_TOKEN`, the
SignPath token as `CSI_CODESIGN_API_TOKEN`, and the `actions: read` permission.

### Release Notes

The GitHub release body is **not** generated from commit messages. It is rendered by
`.github/scripts/render-release-notes.py` from the `CHANGELOG.md` section for the release,
plus a collapsed commit list, the contributor list, and artifact verification instructions.
The workflow writes it to `target/release-notes.md`, which JReleaser publishes verbatim via
`<changelog><external>` in `pom.xml`.

Practical consequence: whatever you write under `## [Unreleased]` is what users read on the
releases page, so write it for them rather than for the repository. `release.sh` refuses to
release if that section is missing, empty, or fails to render.

Rendering requires `GPG_KEY_ID` in the environment — the key users are told to import to
check the signatures. It must be the same value as the `GPG_KEY_ID` repository secret (which
the workflow passes automatically), so that the notes cannot advertise a key other than the
one that signed the assets. It is *not* your `user.signingkey`, which signs commits and tags.

Preview before tagging, or re-render a past release:

```shell
export GPG_KEY_ID=<artifact signing key>
python3 .github/scripts/render-release-notes.py --unreleased
python3 .github/scripts/render-release-notes.py --unreleased --version 0.9.0
python3 .github/scripts/render-release-notes.py --tag v0.8.2
```

Without `--version`, an unreleased preview uses the version Maven builds at HEAD without its
`-SNAPSHOT` suffix, which is always the next patch version.

To repair the notes of an already-published release, `--dry-run` diffs against the live body
and `--publish` replaces it (both require `gh` authentication):

```shell
python3 .github/scripts/render-release-notes.py --tag v0.8.2 --dry-run
python3 .github/scripts/render-release-notes.py --tag v0.8.2 --publish
```

The commit range is auto-detected as the previous version documented in `CHANGELOG.md`. That
is correct for every release from v0.8.2 onwards, but several older tags (`v0.6.3`, `v0.7.0`,
`v0.7.1`, `v0.7.2`, `v0.8.0`) were tagged and never published, so backfilling across them
needs `--previous` to name the last version that actually shipped:

```shell
python3 .github/scripts/render-release-notes.py --tag v0.7.3 --previous v0.6.2 --dry-run
python3 .github/scripts/render-release-notes.py --tag v0.8.1 --previous v0.7.3 --dry-run
```

### Local Dry Run

```shell
# Step 1: build + deploy to local staging directories. main builds X.Y.Z-SNAPSHOT, which the
# release profile's Maven Central check rejects unless told this is not a release.
./mvnw -B -ntp -Prelease -Dpomchecker.release=false -DskipTests clean deploy

# Step 2: stand in for the release assets only CI produces (the native archives, the fat JAR
# and their Sigstore bundles). jreleaser:full-release fails on any missing asset, and on an
# empty one, which it cannot checksum.
VERSION=$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version | tail -n 1)
rm -rf out && mkdir -p out/archives out/cli-fatjar
cp cli/target/codesign-cli-*-bin.jar "out/cli-fatjar/codesign-cli-${VERSION}-bin.jar"
for platform in linux-x86_64.tar.gz linux-aarch_64.tar.gz osx-aarch_64.tar.gz windows-x86_64.zip; do
  echo placeholder > "out/archives/csi-codesign-${VERSION}-${platform}"
done
for asset in out/archives/* out/cli-fatjar/*; do echo '{}' > "${asset}.sigstore.json"; done

# Step 3: render the release notes. This must come after step 1, which deletes target/.
# jreleaser:full-release fails if target/release-notes.md is missing.
python3 .github/scripts/render-release-notes.py --unreleased --output target/release-notes.md

# Step 4: JReleaser dry run (mocked credentials are OK for SNAPSHOT)
JRELEASER_GITHUB_TOKEN=dry_run_token \
  JRELEASER_MAVENCENTRAL_USERNAME=dry_run_user \
  JRELEASER_MAVENCENTRAL_PASSWORD=dry_run_pass \
  JRELEASER_NEXUS2_USERNAME=dry_run_user \
  JRELEASER_NEXUS2_PASSWORD=dry_run_pass \
  ./mvnw -B -ntp -DskipTests jreleaser:full-release -Djreleaser.dry.run=true

# Step 5: confirm JReleaser published the rendered file verbatim
diff target/release-notes.md target/jreleaser/release/CHANGELOG.md

# Step 6: remove the placeholders
rm -rf out
```

A SNAPSHOT dry run cannot exercise everything: JReleaser forces `overwrite = true` and
disables `release.github.issues` for snapshots, so neither the `BODY` update path nor issue
labelling is covered by it. Signing and cataloging (attestations) only run for releases. With
placeholders, it also checks that every asset is wired into the release, not that the real
ones are built or signed correctly.

## Contact

Contact the project developers via the project's "dev" list:

- <https://accounts.eclipse.org/mailing-list/csi-dev>

## Issues

For bug reports and feature requests, please use GitHub Issues:

- <https://github.com/eclipse-csi/codesign-tools/issues>
