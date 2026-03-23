# Eclipse CSI Codesign Tools

[![Maven Central Version](https://img.shields.io/maven-central/v/org.eclipse.csi/codesign-maven-plugin)](https://central.sonatype.com/artifact/org.eclipse.csi/codesign-maven-plugin/overview)
[![GitHub Release](https://img.shields.io/github/v/release/eclipse-csi/codesign-tools)](https://github.com/eclipse-csi/codesign-tools/releases)
[![Build Status on GitHub](https://github.com/eclipse-csi/codesign-tools/actions/workflows/ci.yml/badge.svg?branch:main&workflow:Build)](https://github.com/eclipse-csi/codesign-tools/actions/workflows/ci.yml?query=branch%3Amain)
[![Site Build Status on GitHub](https://github.com/eclipse-csi/codesign-tools/actions/workflows/site.yml/badge.svg?branch:main&workflow:Site)](https://eclipse-csi.github.io/codesign-tools/)
[![EPLv2 License](https://img.shields.io/github/license/eclipse-csi/codesign-tools)](https://github.com/eclipse-csi/codesign-tools/blob/main/LICENSE)

Tools for signing artifacts via the [SignPath](https://about.signpath.io/) REST API:

- **CLI** (`csi-codesign`) — standalone native binary for signing in any CI/CD pipeline
- **Maven Plugin** (`codesign-maven-plugin`) — integrates signing into Maven builds
- **Java API** — integrates signing into Java application (used by both the CLI and the
Maven Plugin)

## Quick Start

### CLI

Download the native binary for your platform from the
[Releases](https://github.com/eclipse-csi/codesign-tools/releases) page and
run:

```bash
csi-codesign sign app.exe \
  --organization-id <ORG_ID> \
  --project-id <PROJECT_SLUG> \
  --signing-policy <POLICY_SLUG> \
  --output app-signed.exe
```

Set `CSI_CODESIGN_API_TOKEN` for authentication.

### Maven Plugin

Add the plugin to your `pom.xml`:

```xml
<plugin>
  <groupId>org.eclipse.csi</groupId>
  <artifactId>codesign-maven-plugin</artifactId>
  <version>VERSION</version>
</plugin>
```

Replace `VERSION` with the latest release from
[Maven Central](https://central.sonatype.com/artifact/org.eclipse.csi/codesign-maven-plugin).

For full installation, authentication, configuration, and troubleshooting documentation,
see **[USAGE.md](USAGE.md)**.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, build instructions, and
the release process.

## License

[Eclipse Public License - v 2.0](LICENSE)
