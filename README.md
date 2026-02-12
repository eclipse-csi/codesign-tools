# Eclipse CSI SignPath Maven Plugin

Maven plugin that signs artifacts via the [SignPath](https://about.signpath.io/) REST API.

## Installation

Add the plugin to your Maven project:

```xml
<plugin>
  <groupId>org.eclipse.csi</groupId>
  <artifactId>signpath-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</plugin>
```

## Usage

```shell
mvn org.eclipse.csi:signpath-maven-plugin:sign \
  -Dsignpath.organizationId=<ORG_ID> \
  -Dsignpath.projectSlug=<PROJECT_SLUG> \
  -Dsignpath.signingPolicySlug=<POLICY_SLUG> \
  -Dsignpath.artifactConfigurationSlug=<CONFIG_SLUG>
```

## Embedded Project Example (Sign Binaries)

For regular project builds, configure the plugin in your `pom.xml` so signing
happens automatically during `package`.

This example signs `.jar`, `.exe`, and `.dmg` files produced in
`${project.build.directory}` and writes signed artifacts to
`${project.build.directory}/signed`.

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.eclipse.csi</groupId>
      <artifactId>signpath-maven-plugin</artifactId>
      <version>1.0.0-SNAPSHOT</version>
      <executions>
        <execution>
          <id>sign-binaries</id>
          <phase>package</phase>
          <goals>
            <goal>sign</goal>
          </goals>
          <configuration>
            <!-- Required SignPath coordinates -->
            <organizationId>${env.SIGNPATH_ORG_ID}</organizationId>
            <projectSlug>my-product</projectSlug>
            <signingPolicySlug>release-signing</signingPolicySlug>
            <artifactConfigurationSlug>default-binary-config</artifactConfigurationSlug>

            <!-- Optional metadata passed to SignPath -->
            <description>${project.artifactId} ${project.version}</description>
            <parameters>
              <buildNumber>${env.BUILD_NUMBER}</buildNumber>
              <gitCommit>${env.GIT_COMMIT}</gitCommit>
            </parameters>

            <!-- Select files to sign from ${project.build.directory} -->
            <includes>
              <include>*.jar</include>
              <include>*.exe</include>
              <include>*.dmg</include>
            </includes>
            <excludes>
              <exclude>*-sources.jar</exclude>
              <exclude>*-javadoc.jar</exclude>
            </excludes>

            <!-- Write signed files to a dedicated folder -->
            <outputDirectory>${project.build.directory}/signed</outputDirectory>

            <!-- Optional polling/HTTP tuning -->
            <pollInterval>5</pollInterval>
            <retryInterval>30</retryInterval>
            <retryTimeout>600</retryTimeout>
            <httpTimeout>300</httpTimeout>
            <connectTimeout>30</connectTimeout>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

After `mvn package`, signed binaries are available in `target/signed`.

Tip: if you omit `outputDirectory`, signed files replace the original files in
`${project.build.directory}`.

## API Token Configuration

The plugin resolves the API token in the following order (first match wins):

1. **Parameter / system property** — `-Dsignpath.apiToken=<TOKEN>` or `<apiToken>` in the plugin configuration
2. **Maven `settings.xml`** — server password with ID `signpath` (or a custom ID via `signpath.serverId`):

   ```xml
   <!-- ~/.m2/settings.xml -->
   <servers>
     <server>
       <id>signpath</id>
       <password>YOUR_API_TOKEN</password>
     </server>
   </servers>
   ```

   Encrypted passwords (via `mvn --encrypt-password`) are supported.

3. **Environment variable** — `SIGNPATH_API_TOKEN`

If none of these are set, the build fails with an error listing all three options.

## Building

```shell
mvn clean verify
```

## Releasing (Maven Central via JReleaser)

This project includes an rlease pipeline based on JReleaser.

- Workflow: `.github/workflows/release.yml`
- Trigger: push a version tag (for example `v1.0.0`) or run manually via
  `workflow_dispatch`
- Build profile: `release`

### Required GitHub Secrets

Configure these repository/environment secrets before running the release
workflow:

- `JRELEASER_MAVENCENTRAL_USERNAME`
- `JRELEASER_MAVENCENTRAL_PASSWORD`
- `JRELEASER_GPG_PUBLIC_KEY` (ASCII-armored)
- `JRELEASER_GPG_SECRET_KEY` (ASCII-armored)
- `JRELEASER_GPG_PASSPHRASE`

### Dry Run

Use the manual workflow trigger and set `dry-run=true` to validate release
configuration without publishing.

### Local Dry Run

```shell
mvn -Prelease -DskipTests clean verify
mvn -Prelease -Djreleaser.dry.run=true jreleaser:full-release
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for details on how to contribute to this project.

## License

[Eclipse Public License - v 2.0](LICENSE)
