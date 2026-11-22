#!/usr/bin/env bash
#
# Attaches a description and user-defined parameters to the signing request. Both show on the
# request in the SignPath web application, which traces a signature back to the build that
# asked for it.
#
# SignPath only keeps the parameters that the artifact configuration declares, and can use them
# to restrict what is signed. The default configuration, "jar-with-parameters" (see
# ../signpath/), requires "version" and only accepts a file named hello-<version>.jar.
#
# Usage: ./04-description-and-params.sh [VERSION]    (default: 1.0.0)

# shellcheck source-path=SCRIPTDIR source=common.sh
source "$(dirname "$0")/common.sh"
require_env CSI_CODESIGN_API_TOKEN SIGNPATH_ORGANIZATION_ID SIGNPATH_PROJECT_ID SIGNPATH_SIGNING_POLICY
require_cli

version="${1:-1.0.0}"
input="$(make_sample_jar "hello-$version.jar")"
output_dir="$WORK_DIR/signed"
git_commit="$(git rev-parse HEAD 2>/dev/null || echo unknown)"

run "${CODESIGN[@]}" sign "$input" \
  --organization-id "$SIGNPATH_ORGANIZATION_ID" \
  --project-id "$SIGNPATH_PROJECT_ID" \
  --signing-policy "$SIGNPATH_SIGNING_POLICY" \
  --artifact-configuration "${SIGNPATH_ARTIFACT_CONFIGURATION:-jar-with-parameters}" \
  --description "Hello $version, signed by the codesign demos" \
  --param "version=$version" \
  --param "buildNumber=${BUILD_NUMBER:-local}" \
  --param "gitCommit=$git_commit" \
  --output-dir "$output_dir"

show_signed "$output_dir/$(basename "$input")"
