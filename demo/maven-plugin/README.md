# Maven plugin demos

Small standalone Maven projects that sign their jars with `codesign-maven-plugin`. Each folder
is a complete project: copy the one closest to your build and adapt the `<configuration>`.

## Setup

1. Java 21+ and Maven 3.10+.
2. Create the artifact configurations of [`../signpath/`](../signpath/) in your SignPath project.
3. Export the environment variables listed in [`../README.md`](../README.md#prerequisites). The
   POMs read the SignPath coordinates from them with `${env.…}`, and the plugin reads the token
   from `CSI_CODESIGN_API_TOKEN` (except in `project-artifact`, which uses `settings.xml`).

Then run `mvn package` in a demo folder, and check the result with `jarsigner -verify`.

## Demos

| Demo | Shows | Artifact configuration |
| --- | --- | --- |
| [`adhoc/`](adhoc/) | Signing from the command line, without declaring the plugin in the POM | `jar` |
| [`project-artifact/`](project-artifact/) | The main jar signed in place during `package`; token from `settings.xml` | `jar` |
| [`includes-output-dir/`](includes-output-dir/) | Files selected with `<includes>`/`<excludes>`, written to `<outputDirectory>`; `<description>` and `<parameters>` | `binaries-in-zip` |
| [`attached-artifacts/`](attached-artifacts/) | Main and attached artifacts signed in place, so `install`/`deploy` publish them signed | `jars-in-zip` |
| [`multi-execution/`](multi-execution/) | Two executions: two requests, with different configurations or policies | `jar`, `jars-in-zip` |
| [`profile-and-skip/`](profile-and-skip/) | Signing only with `-Pcodesign`; skipping with `-Dcsi.codesign.skip` or `CSI_CODESIGN_SKIP_SIGNING` | `jar` |

The trusted build system flow (`<submissionMode>github-actions</submissionMode>`), required by
Eclipse Foundation signing policies, only runs in GitHub Actions: see
[`../github-actions/maven-plugin-trusted-build.yml`](../github-actions/maven-plugin-trusted-build.yml).

## What to remember

- **One execution is one signing request.** One selected file is sent as is; several are packed
  at the root of a zip file, so they need a `<zip-file>` artifact configuration and distinct
  names. Use one `<execution>` per set of files that needs its own request.
- **Two ways to select files**, merged: the Maven artifacts (`signProjectArtifact`, `auto` by
  default, and `signAttachedArtifacts`, `false` by default), and a scan of
  `${project.build.directory}` with `<includes>`/`<excludes>` globs.
- **In place or not**: without `<outputDirectory>`, the signed files replace the originals,
  which is what later `install`/`deploy` phases publish.
- **Token lookup order**: `settings.xml` server `codesign` (`<serverId>`), then
  `CSI_CODESIGN_API_TOKEN`, then `api.token` in `~/.config/eclipse-csi-codesign/config.properties`.
- `<failOnNoFilesFound>true</failOnNoFilesFound>` turns an empty selection into a build failure
  instead of a warning.

See the [`codesign:codesign` goal reference](https://eclipse-csi.github.io/codesign-tools/latest/maven-plugin/codesign-mojo.html)
for every parameter.

> The empty [`.mvn/extensions.xml`](.mvn/extensions.xml) only keeps this repository's build
> extensions away from the demos. A copy of a demo doesn't need it.
