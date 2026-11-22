#!/usr/bin/env bash
#
# Signs one file and writes the signed copy to another path with --output. The input is left
# unchanged.
#
# One file is sent to SignPath as is, so the artifact configuration describes the file itself
# (default: "jar", see ../signpath/).
#
# Usage: ./01-single-file.sh [FILE]    (default: a generated sample jar)

# shellcheck source-path=SCRIPTDIR source=common.sh
source "$(dirname "$0")/common.sh"
require_env CSI_CODESIGN_API_TOKEN SIGNPATH_ORGANIZATION_ID SIGNPATH_PROJECT_ID SIGNPATH_SIGNING_POLICY
require_cli

input="${1:-$(make_sample_jar hello.jar)}"
output="$WORK_DIR/signed-$(basename "$input")"

run "${CODESIGN[@]}" sign "$input" \
  --organization-id "$SIGNPATH_ORGANIZATION_ID" \
  --project-id "$SIGNPATH_PROJECT_ID" \
  --signing-policy "$SIGNPATH_SIGNING_POLICY" \
  --artifact-configuration "${SIGNPATH_ARTIFACT_CONFIGURATION:-jar}" \
  --output "$output"

show_signed "$output"
