# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

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
