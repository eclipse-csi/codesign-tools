#!/usr/bin/env bash
#
# Builds the jar, then signs it in place with the codesign plugin, invoked by its full
# coordinates: no change to the POM is needed. Works the same on any jar/war/ear project.
#
# The token comes from CSI_CODESIGN_API_TOKEN (or settings.xml, see ../project-artifact/).

set -euo pipefail
cd "$(dirname "$0")"

mvn -B package org.eclipse.csi:codesign-maven-plugin:0.11.0:codesign \
  -Dcsi.codesign.organizationId="$SIGNPATH_ORGANIZATION_ID" \
  -Dcsi.codesign.projectId="$SIGNPATH_PROJECT_ID" \
  -Dcsi.codesign.signingPolicy="$SIGNPATH_SIGNING_POLICY" \
  -Dcsi.codesign.artifactConfiguration=jar \
  -Dcsi.codesign.failOnNoFilesFound=true

# jarsigner -verify also succeeds on unsigned jars: check that it says "jar verified."
jarsigner -verify target/adhoc-1.0.0-SNAPSHOT.jar | tee /dev/stderr | grep -qF 'jar verified.'
