# Sign the project artifact during the build

The most common setup: an `<execution>` of the `codesign` goal, which binds to `package` by
default, signs the main jar in place. Later phases (`install`, `deploy`) then publish the
signed jar.

The API token comes from Maven's `settings.xml`: the password of the `<server>` whose id is
`codesign` (the `<serverId>` parameter). [`settings.xml`](settings.xml) reads it from the
environment so that the demo writes no token to disk; in real use, put the server in
`~/.m2/settings.xml` with an encrypted password.

```bash
mvn -s settings.xml package
jarsigner -verify target/project-artifact-1.0.0-SNAPSHOT.jar
```

**Needs:** artifact configuration `jar`.
**Result:** `target/project-artifact-1.0.0-SNAPSHOT.jar` is signed.
