<p align="center">
  <a href="https://central.sonatype.com/artifact/org.eclipse.csi/codesign-maven-plugin/overview"><img alt="Maven Central Version" src="https://img.shields.io/maven-central/v/org.eclipse.csi/codesign-maven-plugin"></a>
  <a href="https://github.com/eclipse-csi/codesign-tools/releases"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/eclipse-csi/codesign-tools"></a>
  <a href="https://github.com/eclipse-csi/codesign-tools/actions/workflows/ci.yml?query=branch%3Amain"><img alt="Build Status on GitHub" src="https://github.com/eclipse-csi/codesign-tools/actions/workflows/ci.yml/badge.svg?branch:main&workflow:Build" /></a>
  <a href="https://eclipse-csi.github.io/codesign-tools/"><img alt="Site Build Status on GitHub" src="https://github.com/eclipse-csi/codesign-tools/actions/workflows/site.yml/badge.svg?branch:main&workflow:Site" /></a>
  <a href="https://github.com/eclipse-csi/codesign-tools/blob/main/LICENSE"><img alt="EPLv2 License" src="https://img.shields.io/github/license/eclipse-csi/codesign-tools" /></a>
</p>

# Eclipse CSI Codesign Tools

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
