# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [v0.8.2] - 2026-08-06

### Fixed

* Bump `native-maven-plugin` to 1.1.7 to fix the native build failing with a missing
  `org.apache.maven.shared.utils.logging.MessageUtils` class (upstream regression in 1.1.6).
* Unblock the `windows-x86_64` native build, broken since harden-runner v2.20.0 began enforcing
  `egress-policy: block` on Windows runners: allow the Let's Encrypt CRL distribution points
  (`*.c.lencr.org`) that the Windows TLS stack needs to reach Maven Central, and prefer Git's
  OpenSSL `curl` (with an `mvnw.cmd` fallback) so a future intermediate rotation cannot stale
  that allowlist.

### Changed

* Pass `github-token` to `setup-graalvm` in the native build to avoid unauthenticated
  `api.github.com` rate limits.
* Update pinned GitHub Actions (checkout v7.0.1, setup-java v5.6.0, setup-uv v9.0.0,
  codeql-action v4.37.3, setup-graalvm v1.6.3, harden-runner v2.20.0, trufflehog v3.96.0,
  zizmor-action v0.6.1, reviewdog action-actionlint v1.73.0, action-markdownlint v0.28.0).
* Update Maven build dependencies (JUnit Jupiter 6.1.2, Kotlin 2.4.10, flatten-maven-plugin 1.8.0).
* Update pinned tool versions (cdxgen 12.8.2, cyclonedx-cli 0.33.1, opengrep 1.26.0,
  trufflehog 3.96.0, prek 0.4.11, ruff 0.16.0) and prek hook revisions (zizmor v1.29.0,
  ruff v0.16.1, markdownlint-cli v0.49.1, betterleaks v1.7.3, trufflehog v3.96.0).

## [v0.8.1] - 2026-07-06

### Changed

* Move workflow tooling manifests from `.github/tools` to `tools/github-workflow-tools` so Dependabot can manage them.
* Restrict Dependabot updates for the workflow tools npm ecosystem to direct dependencies.
* Update pinned GitHub Actions (checkout v7, dependency-review-action v5, codeql-action, harden-runner, setup-java, trivy-action, setup-uv, setup-graalvm, cosign-installer, trufflehog, zizmor-action, reviewdog actions, attest-build-provenance).
* Update Maven build dependencies (Maven 3.9.16, okhttp 5.4.0, JUnit Jupiter 6.1.1, Kotlin 2.4.0, and the maven-plugins group).
* Update pinned tool versions (cdxgen, cyclonedx-cli, opengrep, poutine, trufflehog, prek, ruff).

### Fixed

* Disable base64 line-wrapping when building the gh-pages push auth header in the site workflow.

## [v0.7.3] - 2026-05-03

### Security

* Switch from gitleaks to betterleaks for secret scanning in CI.
* Add `dependency-review-action` for license and vulnerability vetting of dependencies introduced in PRs.
* Check for high and critical vulnerabilities in the release script.

### Added

* Integrate poutine into CI guardrail: violations are reported as code scanning alerts. Trusted actions from 'unverified' creators are excluded from poutine checks.
* Include poutine in the pinned-tool-versions update check.

### Changed

* Drop macOS Intel (`osx-x86_64`) builds — no longer supported by GraalVM.
* Remove `osx-x86_64` from JReleaser configuration (the binary is no longer built).
* Upgrade GraalVM distribution to 25 and `graalvm/setup-graalvm` action to v1.5.2.
* Rename `reviewdog` workflow to `prs-review`.
* Extend allowed network endpoints in CI workflows to include `raw.githubusercontent.com` and `api.deps.dev`.
* Move `.poutine.yml` to `.github/poutine.yml`.
* Switch Dependabot update interval from weekly to daily.
* Update pinned tool versions and CI/build dependencies (python-workflow-tools, setup-uv, kotlin-stdlib, prek, native-maven-plugin, gson, cdxgen, harden-runner, trufflehog).

### Fixed

* Fix hook-coverage script and documentation following the rename of the reviewdog workflow to prs-review.
* Fix fetch of `ci/update-pinned-tool-versions` script in workflow.
* Fix README lint issue.

## [v0.7.2] - 2026-05-03

* Sacrificed to the CI/CD gods.

## [v0.7.1] - 2026-05-03

* Sacrificed to the CI/CD gods.

## [v0.7.0] - 2026-05-03

* Sacrificed to the CI/CD gods.

## [v0.6.3] - 2026-03-31

* Never released officially.

## [v0.6.2] - 2026-03-20

### Changed

* Update build and ci dependencies

## [v0.6.1] - 2026-03-14

### Fixed

* Fit shellcheck calls in reviewdog workflow

### Changed

* Grant `issues:write` permission for JReleaser release labeling

## [v0.6.0] - 2026-03-14

### Security

* Use here-strings instead of echo piping for GPG key import in release workflow to avoid exposing secrets in process listings.

### Fixed

* Fix Nexus2 snapshot deployer `closeRepository` and `releaseRepository` settings (should be `false` for snapshot deployments).

### Changed

* Add explicit JReleaser `release.github` configuration with conventional-commits changelog preset, contributor listing, and issue linking.
* Add `jreleaser:catalog` step to create GitHub attestations for distribution artifacts (native binaries and fat JAR).
* Consolidate duplicate build steps in release workflow (publish and dry-run used identical commands).
* Upgrade `upload-artifact` to v7.0.0 in release workflow for consistency with native build workflow.

## [v0.5.13] - 2026-03-14

* Initial relase
* All previous releases were for testing and automating the release workflow.
