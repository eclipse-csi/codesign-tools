# Sign the main and attached artifacts in place

With `<signAttachedArtifacts>true</signAttachedArtifacts>`, every artifact attached to the
project when the plugin runs is signed together with the main one, here a `-tools` jar. They
are replaced in place, so `mvn install` and `mvn deploy` publish the signed jars.

```bash
mvn package
jarsigner -verify target/attached-artifacts-1.0.0-SNAPSHOT.jar
jarsigner -verify target/attached-artifacts-1.0.0-SNAPSHOT-tools.jar
```

**Needs:** artifact configuration `jars-in-zip` (two files are packed in a zip).
**Result:** both jars in `target/` are signed.

Every attached artifact is selected, whatever its type. Attach artifacts that must not be signed,
such as `-sources` jars or SBOMs, after this execution (bind it to an earlier phase or declare it
first), or select files with `<includes>` instead (see [`../includes-output-dir/`](../includes-output-dir/)).
