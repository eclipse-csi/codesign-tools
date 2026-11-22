#!/usr/bin/env bash
#
# Reads the API token from a properties file instead of the CSI_CODESIGN_API_TOKEN environment
# variable, which takes precedence and is therefore removed for this run.
#
# The file holds one key, api.token, and must not be readable by other users:
#
#   mkdir -p ~/.config/eclipse-csi-codesign
#   (umask 077 && printf 'api.token=%s\n' "<token>" > ~/.config/eclipse-csi-codesign/config.properties)
#
# That is the default location, so --config-file is only needed for another path. It is passed
# explicitly here to show the option.
#
# Usage: ./05-config-file-token.sh [FILE]    (default: a generated sample jar)
#        CSI_CODESIGN_CONFIG_FILE=/path/to/config.properties ./05-config-file-token.sh

# shellcheck source-path=SCRIPTDIR source=common.sh
source "$(dirname "$0")/common.sh"
require_env SIGNPATH_ORGANIZATION_ID SIGNPATH_PROJECT_ID SIGNPATH_SIGNING_POLICY
require_cli

config_file="${CSI_CODESIGN_CONFIG_FILE:-$HOME/.config/eclipse-csi-codesign/config.properties}"
[[ -f "$config_file" ]] || die "$config_file does not exist: create it as shown at the top of $0"

input="${1:-$(make_sample_jar hello.jar)}"
output="$WORK_DIR/signed-$(basename "$input")"

run env -u CSI_CODESIGN_API_TOKEN "${CODESIGN[@]}" sign "$input" \
  --config-file "$config_file" \
  --organization-id "$SIGNPATH_ORGANIZATION_ID" \
  --project-id "$SIGNPATH_PROJECT_ID" \
  --signing-policy "$SIGNPATH_SIGNING_POLICY" \
  --artifact-configuration "${SIGNPATH_ARTIFACT_CONFIGURATION:-jar}" \
  --output "$output"

show_signed "$output"
