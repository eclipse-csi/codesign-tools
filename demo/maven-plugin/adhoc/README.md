# Ad-hoc signing from the command line

Signs the main jar of a project that doesn't declare the plugin at all: the goal is invoked by
its full coordinates, and configured with `-Dcsi.codesign.*` properties. Handy to try signing,
or to sign an existing project without editing its POM.

```bash
./run.sh
```

which runs:

```bash
mvn package org.eclipse.csi:codesign-maven-plugin:0.12.0:codesign \
  -Dcsi.codesign.organizationId="$SIGNPATH_ORGANIZATION_ID" \
  -Dcsi.codesign.projectId="$SIGNPATH_PROJECT_ID" \
  -Dcsi.codesign.signingPolicy="$SIGNPATH_SIGNING_POLICY" \
  -Dcsi.codesign.artifactConfiguration=jar \
  -Dcsi.codesign.failOnNoFilesFound=true
```

`package` builds the jar, then the `codesign` goal signs it in place, in the same Maven session.
Only the main artifact is selected (`signProjectArtifact=auto`), so the `jar` artifact
configuration applies.

**Needs:** artifact configuration `jar`; token in `CSI_CODESIGN_API_TOKEN`.
**Result:** `target/adhoc-1.0.0-SNAPSHOT.jar` is signed.
