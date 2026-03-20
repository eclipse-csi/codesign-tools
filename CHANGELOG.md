# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
