#!/usr/bin/env bash
#
# Signs a file in place: the signed file replaces the original. The CLI only does this with the
# explicit --force-overwrite opt-in. The replacement is atomic, and nothing is written unless
# the request completes.
#
# Default artifact configuration: "jar" (one file, sent as is). To sign several files in place,
# pass them all and use a <zip-file> configuration, e.g. SIGNPATH_ARTIFACT_CONFIGURATION=jars-in-zip.
#
# Usage: ./03-in-place.sh [FILE...]    (default: a generated sample jar)
#
# WARNING: the files you pass are overwritten.

# shellcheck source-path=SCRIPTDIR source=common.sh
source "$(dirname "$0")/common.sh"
require_env CSI_CODESIGN_API_TOKEN SIGNPATH_ORGANIZATION_ID SIGNPATH_PROJECT_ID SIGNPATH_SIGNING_POLICY
require_cli

if (($# > 0)); then
  inputs=("$@")
else
  inputs=("$(make_sample_jar hello.jar)")
fi

run "${CODESIGN[@]}" sign "${inputs[@]}" \
  --organization-id "$SIGNPATH_ORGANIZATION_ID" \
  --project-id "$SIGNPATH_PROJECT_ID" \
  --signing-policy "$SIGNPATH_SIGNING_POLICY" \
  --artifact-configuration "${SIGNPATH_ARTIFACT_CONFIGURATION:-jar}" \
  --force-overwrite

show_signed "${inputs[@]}"
