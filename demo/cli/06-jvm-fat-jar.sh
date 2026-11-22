#!/usr/bin/env bash
#
# Runs the CLI on the JVM, from the -bin fat jar published on Maven Central, instead of the
# native binary. Useful on platforms without a native build, or where a JDK is already set up.
#
# The fat jar is itself signed by the Eclipse Foundation through SignPath: this demo checks its
# SHA-256 against Maven Central, then its jar signature, before running it.
#
# Usage: ./06-jvm-fat-jar.sh [FILE]    (default: a generated sample jar)
#        CODESIGN_VERSION=0.11.0 ./06-jvm-fat-jar.sh

# shellcheck source-path=SCRIPTDIR source=common.sh
source "$(dirname "$0")/common.sh"
require_env CSI_CODESIGN_API_TOKEN SIGNPATH_ORGANIZATION_ID SIGNPATH_PROJECT_ID SIGNPATH_SIGNING_POLICY
command -v java >/dev/null || die "java (21 or later) is needed"

version="${CODESIGN_VERSION:-0.11.0}"
fat_jar="$WORK_DIR/codesign-cli-$version-bin.jar"
url="https://repo1.maven.org/maven2/org/eclipse/csi/codesign-cli/$version/codesign-cli-$version-bin.jar"

run curl --fail --silent --show-error --location --output "$fat_jar" "$url"
expected="$(curl --fail --silent --show-error --location "$url.sha256" | cut -d' ' -f1)"
if command -v sha256sum >/dev/null; then
  actual="$(sha256sum "$fat_jar" | cut -d' ' -f1)"
else
  actual="$(shasum -a 256 "$fat_jar" | cut -d' ' -f1)"
fi
[[ "$actual" == "$expected" ]] || die "SHA-256 mismatch for $fat_jar: $actual, expected $expected"
if command -v jarsigner >/dev/null; then
  show_signed "$fat_jar"
fi

input="${1:-$(make_sample_jar hello.jar)}"
output="$WORK_DIR/signed-$(basename "$input")"

run java -jar "$fat_jar" sign "$input" \
  --organization-id "$SIGNPATH_ORGANIZATION_ID" \
  --project-id "$SIGNPATH_PROJECT_ID" \
  --signing-policy "$SIGNPATH_SIGNING_POLICY" \
  --artifact-configuration "${SIGNPATH_ARTIFACT_CONFIGURATION:-jar}" \
  --output "$output"

show_signed "$output"
