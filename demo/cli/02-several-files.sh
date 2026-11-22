#!/usr/bin/env bash
#
# Signs several files in ONE signing request and writes them to a directory with --output-dir,
# under their original names.
#
# The files are packed at the root of a zip file, so the artifact configuration has a
# <zip-file> root element (default: "jars-in-zip", see ../signpath/) and the files need distinct
# names. A policy that requires approval asks for a single approval.
#
# Usage: ./02-several-files.sh [FILE...]    (default: two generated sample jars)
#
# Pass at least two files: a single file is sent as is, which a <zip-file> configuration
# rejects. Shell globs work, e.g. ./02-several-files.sh target/*.jar

# shellcheck source-path=SCRIPTDIR source=common.sh
source "$(dirname "$0")/common.sh"
require_env CSI_CODESIGN_API_TOKEN SIGNPATH_ORGANIZATION_ID SIGNPATH_PROJECT_ID SIGNPATH_SIGNING_POLICY
require_cli

if (($# > 0)); then
  inputs=("$@")
else
  inputs=("$(make_sample_jar app.jar)" "$(make_sample_jar library.jar)")
fi
output_dir="$WORK_DIR/signed"

run "${CODESIGN[@]}" sign "${inputs[@]}" \
  --organization-id "$SIGNPATH_ORGANIZATION_ID" \
  --project-id "$SIGNPATH_PROJECT_ID" \
  --signing-policy "$SIGNPATH_SIGNING_POLICY" \
  --artifact-configuration "${SIGNPATH_ARTIFACT_CONFIGURATION:-jars-in-zip}" \
  --output-dir "$output_dir"

signed=()
for input in "${inputs[@]}"; do
  signed+=("$output_dir/$(basename "$input")")
done
show_signed "${signed[@]}"
