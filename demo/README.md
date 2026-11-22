# Demos

Runnable examples of the ways to sign binaries with SignPath using the tools of this
repository. Each folder is self-contained: copy the one that matches your setup and adapt it.

| I want to… | Demo |
| --- | --- |
| Sign files from a shell or any CI system | [`cli/`](cli/) |
| Sign jars as part of a Maven build | [`maven-plugin/`](maven-plugin/) |
| Sign in a GitHub Actions workflow (required by Eclipse Foundation signing policies) | [`github-actions/`](github-actions/) |
| Set up the SignPath side (artifact configurations, policy, token) | [`signpath/`](signpath/) |

## Scenarios

| Scenario | CLI | Maven plugin | GitHub Actions |
| --- | --- | --- | --- |
| Sign one file, write it elsewhere | [`01-single-file.sh`](cli/01-single-file.sh) | — | — |
| Sign several files in one request | [`02-several-files.sh`](cli/02-several-files.sh) | [`attached-artifacts/`](maven-plugin/attached-artifacts/), [`includes-output-dir/`](maven-plugin/includes-output-dir/) | [`sign-with-action.yml`](github-actions/sign-with-action.yml) |
| Sign in place | [`03-in-place.sh`](cli/03-in-place.sh) | [`project-artifact/`](maven-plugin/project-artifact/) | [`sign-with-action.yml`](github-actions/sign-with-action.yml) |
| Description and user-defined parameters | [`04-description-and-params.sh`](cli/04-description-and-params.sh) | [`includes-output-dir/`](maven-plugin/includes-output-dir/) | [`sign-with-action.yml`](github-actions/sign-with-action.yml) |
| API token from a file | [`05-config-file-token.sh`](cli/05-config-file-token.sh) | [`project-artifact/`](maven-plugin/project-artifact/) (`settings.xml`) | — |
| Run on the JVM instead of the native binary | [`06-jvm-fat-jar.sh`](cli/06-jvm-fat-jar.sh) | — | — |
| No change to the build, one-off signing | — | [`adhoc/`](maven-plugin/adhoc/) | — |
| Different policies for different files | — | [`multi-execution/`](maven-plugin/multi-execution/) | — |
| Sign only when asked, skip otherwise | — | [`profile-and-skip/`](maven-plugin/profile-and-skip/) | — |
| Trusted build system (GitHub connector) | — | [`maven-plugin-trusted-build.yml`](github-actions/maven-plugin-trusted-build.yml) | [`sign-with-action.yml`](github-actions/sign-with-action.yml) |
| Direct REST API upload from GitHub Actions | — | — | [`cli-direct-mode.yml`](github-actions/cli-direct-mode.yml) |
| Sign and notarize a macOS app and disk image | [`07-macos-app.sh`](cli/07-macos-app.sh) | — | [`macos-sign-and-notarize.yml`](github-actions/macos-sign-and-notarize.yml) |

## Prerequisites

1. A SignPath project, signing policy and artifact configurations, set up as described in
   [`signpath/`](signpath/).
2. The API token of a SignPath CI user that is a submitter of the policy.
3. For the CLI demos, `csi-codesign` on your `PATH`
   ([installation](../USAGE.md#cli)) and a JDK (the demos build sample jars with `jar`).
4. For the Maven demos, Java 21+ and Maven 3.10+.

All demos read the SignPath coordinates from the same environment variables:

```bash
export CSI_CODESIGN_API_TOKEN=...            # API token of the SignPath CI user
export SIGNPATH_ORGANIZATION_ID=...          # organization ID (a UUID)
export SIGNPATH_PROJECT_ID=my-project        # project slug
export SIGNPATH_SIGNING_POLICY=test-signing  # signing policy slug
```

The artifact configuration is chosen by each demo, which uses the slugs of
[`signpath/`](signpath/) by default. The GitHub Actions demos read the same values from
repository variables and secrets instead.

Signing policies with **trusted build system verification**, as used by Eclipse Foundation
projects, only accept requests submitted from GitHub Actions through the SignPath GitHub
connector: with them, only the GitHub Actions demos work. The others need a policy that accepts
direct uploads, e.g. a test-signing policy. The macOS demos need a policy without trusted build
system verification in any case: SignPath refuses hash signing under such policies (see
[`signpath/`](signpath/README.md#macos-hash-signing)).

For every option, see [USAGE.md](../USAGE.md).
