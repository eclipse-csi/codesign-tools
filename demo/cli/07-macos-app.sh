#!/usr/bin/env bash
#
# Signs a macOS app with the SignPath macOS CryptoTokenKit, wraps it in a disk image, signs the
# disk image, and notarizes and staples it when App Store Connect API credentials are set.
#
# macOS only. Unlike the other demos, it needs a SignPath project whose default artifact
# configuration is of type "hash signing data", and a signing policy with a Developer ID
# Application certificate and without trusted build system verification: see
# ../signpath/README.md#macos-hash-signing.
#
# Each `macos sign` invocation starts the CryptoTokenKit, waits for it to publish the certificate
# of the signing policy in its token (up to ~20 s), signs, and stops it again.
#
# Usage: ./07-macos-app.sh [APP]    (default: a sample app compiled with swiftc)
#
# Environment, on top of CSI_CODESIGN_API_TOKEN and the SIGNPATH_* variables:
#   MACOS_SIGNING_IDENTITY   optional: name of the certificate, or part of it, e.g.
#                            "Developer ID Application: Example AISBL" (default: the
#                            certificate of the signing policy)
#   CSI_CODESIGN_NOTARY_KEY, CSI_CODESIGN_NOTARY_KEY_ID, CSI_CODESIGN_NOTARY_ISSUER
#                            optional: App Store Connect API key (.p8 content, key ID, issuer
#                            ID) to notarize with

# shellcheck source-path=SCRIPTDIR source=common.sh
source "$(dirname "$0")/common.sh"
[[ "$(uname -s)" == Darwin ]] || die "this demo runs on macOS only"
require_env CSI_CODESIGN_API_TOKEN SIGNPATH_ORGANIZATION_ID SIGNPATH_PROJECT_ID SIGNPATH_SIGNING_POLICY
require_cli

# make_sample_app: compiles a minimal app bundle in WORK_DIR and prints its path.
make_sample_app() {
  command -v swiftc >/dev/null ||
    die "swiftc (Xcode command line tools) is needed to build the sample app; pass your own .app instead"
  local app="$WORK_DIR/Sample.app"
  mkdir -p "$app/Contents/MacOS"
  cat >"$app/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key><string>Sample</string>
    <key>CFBundleIdentifier</key><string>org.eclipse.csi.codesign.demo</string>
    <key>CFBundleName</key><string>Sample</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
</dict>
</plist>
EOF
  echo 'print("Sample app signed by the Eclipse CSI codesign demos.")' >"$WORK_DIR/main.swift"
  swiftc -O -o "$app/Contents/MacOS/Sample" "$WORK_DIR/main.swift" >&2
  echo "$app"
}

app="${1:-$(make_sample_app)}"
name="$(basename "$app" .app)"
signed_app="$WORK_DIR/signed/$name.app"
dmg="$WORK_DIR/signed/$name.dmg"
signpath=(
  --organization-id "$SIGNPATH_ORGANIZATION_ID"
  --project-id "$SIGNPATH_PROJECT_ID"
  --signing-policy "$SIGNPATH_SIGNING_POLICY"
)
if [[ -n "${MACOS_SIGNING_IDENTITY:-}" ]]; then
  signpath+=(--identity "$MACOS_SIGNING_IDENTITY")
fi

# 1. The app, from the inside out: nested code first, the bundle last, all with the hardened
#    runtime and a secure timestamp. The input stays unsigned; the copy goes to signed/.
run "${CODESIGN[@]}" macos sign "${signpath[@]}" --output-dir "$WORK_DIR/signed" "$app"

# 2. The disk image, built from the signed app, then signed itself.
run hdiutil create -quiet -format UDZO -volname "$name" -srcfolder "$signed_app" "$dmg"
run "${CODESIGN[@]}" macos sign "${signpath[@]}" --force-overwrite "$dmg"

# 3. Notarization covers the app inside the disk image; the ticket is stapled to the disk image.
if [[ -n "${CSI_CODESIGN_NOTARY_KEY:-}" ]]; then
  run "${CODESIGN[@]}" macos notarize "$dmg"
else
  echo "CSI_CODESIGN_NOTARY_KEY is not set: skipping notarization"
fi

echo
echo "Signed files:"
for file in "$signed_app" "$dmg"; do
  echo "  $file"
  codesign -dv "$file" 2>&1 | grep -E '^(Authority|Timestamp|Runtime Version)=' | sed 's/^/    /'
done
