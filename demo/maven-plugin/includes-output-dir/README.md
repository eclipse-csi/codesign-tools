# Select files by pattern, write signed copies elsewhere

The build makes three jars: the main one, a `-tools` jar and a `-sources` jar. The plugin scans
`target/` with `<includes>` (`*.jar`, `*.exe`) and `<excludes>` (`*-sources.jar`,
`*-javadoc.jar`), which selects the main and `-tools` jars. They are signed in **one** request,
packed at the root of a zip file, and the signed copies are written to `target/signed/`; the
originals are left untouched.

The request carries a `<description>` and two user-defined `<parameters>`, `buildNumber` and
`gitCommit`, shown on the request in SignPath. They are only kept because the `binaries-in-zip`
artifact configuration declares them. Pass the real values from CI:

```bash
mvn package -Dbuild.number="$BUILD_NUMBER" -Dgit.commit="$(git rev-parse HEAD)"
jarsigner -verify target/signed/includes-output-dir-1.0.0-SNAPSHOT.jar
jarsigner -verify target/signed/includes-output-dir-1.0.0-SNAPSHOT-tools.jar
```

**Needs:** artifact configuration `binaries-in-zip`.
**Result:** signed copies of the two jars in `target/signed/`.

Patterns are matched against paths relative to `<baseDirectory>` (default
`${project.build.directory}`): `*.jar` only matches files directly in `target/`, `**/*.jar` any
depth.
