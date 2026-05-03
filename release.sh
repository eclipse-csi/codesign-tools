#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 [--dry-run] <RELEASE_VERSION> <NEXT_DEV_VERSION>"
  echo
  echo "Arguments:"
  echo "  RELEASE_VERSION   The version to release (e.g., 0.5.12)"
  echo "  NEXT_DEV_VERSION   The next development version (e.g., 0.5.13-SNAPSHOT)"
  echo
  echo "Options:"
  echo "  --dry-run         Show what would be done without making any changes"
  exit 1
}

DRY_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    -h|--help)
      usage
      ;;
    -*)
      echo "Error: Unknown option $1"
      usage
      ;;
    *)
      break
      ;;
  esac
done

if [[ $# -ne 2 ]]; then
  echo "Error: Expected 2 arguments, got $#"
  usage
fi

RELEASE_VERSION="$1"
NEXT_DEV_VERSION="$2"

if "${DRY_RUN}"; then
  echo "[DRY RUN] No changes will be made."
  echo
fi

# Verify that required tools are available
for cmd in git sed gpg; do
  if ! command -v "$cmd" &>/dev/null; then
    echo "Error: Required command '$cmd' is not installed."
    exit 1
  fi
done
echo "✅ Required tools (git, sed, gpg) are available."

# Verify that the Maven wrapper is available and executable
if [[ ! -x "./mvnw" ]]; then
  echo "Error: Maven wrapper (mvnw) not found or not executable."
  exit 1
fi
echo "✅ Maven wrapper (mvnw) is available and executable."

# Verify that RELEASE_VERSION is in semver format
if ! [[ "${RELEASE_VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Error: RELEASE_VERSION must be in semver format (e.g., 0.5.12)"
  exit 1
fi
echo "✅ Release version (${RELEASE_VERSION}) is in valid semver format."

# Verify that NEXT_DEV_VERSION is in semver format with -SNAPSHOT suffix
if ! [[ "${NEXT_DEV_VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]]; then
  echo "Error: NEXT_DEV_VERSION must be in semver format with -SNAPSHOT suffix (e.g., 0.5.13-SNAPSHOT)"
  exit 1
fi
echo "✅ Next development version (${NEXT_DEV_VERSION}) is in valid semver-SNAPSHOT format."

# Verify that NEXT_DEV_VERSION is greater than RELEASE_VERSION
NEXT_DEV_BASE="${NEXT_DEV_VERSION%-SNAPSHOT}"
if [[ "$(printf '%s\n' "${RELEASE_VERSION}" "${NEXT_DEV_BASE}" | sort -V | tail -1)" != "${NEXT_DEV_BASE}" ]] \
   || [[ "${RELEASE_VERSION}" == "${NEXT_DEV_BASE}" ]]; then
  echo "Error: Next development version (${NEXT_DEV_VERSION}) must be greater than release version (${RELEASE_VERSION})"
  exit 1
fi
echo "✅ Next development version (${NEXT_DEV_VERSION}) is greater than release version (${RELEASE_VERSION})."

# Verify that GPG signing is properly configured
GPG_KEY=$(git config --get user.signingkey 2>/dev/null || true)
if [[ -z "${GPG_KEY}" ]]; then
  echo "Error: No GPG signing key configured. Please set one with: git config --global user.signingkey <KEY_ID>"
  exit 1
fi
if ! gpg --list-secret-keys "${GPG_KEY}" &>/dev/null; then
  echo "Error: GPG secret key '${GPG_KEY}' is not available. Please ensure the key is imported and accessible."
  exit 1
fi
echo "✅ GPG signing key (${GPG_KEY}) is configured and available."

# Verify that the git working directory is clean (except for untracked files)
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "Error: Git working directory is not clean. Please commit or stash your changes before running this script."
  exit 1
fi
echo "✅ Git working directory is clean."

# Verify that the current branch is main
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [[ "${CURRENT_BRANCH}" != "main" ]]; then
  echo "Error: Current branch is ${CURRENT_BRANCH}. Please switch to main branch before running this script."
  exit 1
fi
echo "✅ Current branch is main."

# Verify that the local main branch is up to date (or ahead without diverging) with origin/main
git fetch -q origin main
LOCAL_COMMIT=$(git rev-parse main)
REMOTE_COMMIT=$(git rev-parse origin/main)
if [[ "${LOCAL_COMMIT}" != "${REMOTE_COMMIT}" ]]; then
  if git merge-base --is-ancestor "${LOCAL_COMMIT}" "${REMOTE_COMMIT}"; then
    echo "Error: Local main branch is behind origin/main. Please pull the latest changes before running this script."
    exit 1
  elif git merge-base --is-ancestor "${REMOTE_COMMIT}" "${LOCAL_COMMIT}"; then
    echo "Local main branch is ahead of origin/main. Changes will be pushed along with this script."
  else
    echo "Error: Local main branch has diverged from origin/main. Please resolve the divergence before running this script."
    exit 1
  fi
fi
echo "✅ Local main branch is up to date with origin/main."

# Verify that the current POM version is the SNAPSHOT of the release version
CURRENT_POM_VERSION=$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)
if [[ "${CURRENT_POM_VERSION}" != "${RELEASE_VERSION}-SNAPSHOT" ]]; then
  echo "Error: Current POM version is ${CURRENT_POM_VERSION}, expected ${RELEASE_VERSION}-SNAPSHOT"
  exit 1
fi
echo "✅ Current POM version (${CURRENT_POM_VERSION}) matches expected SNAPSHOT version."

# Verify that the tag for the release version does not already exist
if git rev-parse "v${RELEASE_VERSION}" >/dev/null 2>&1; then
  echo "Error: Tag v${RELEASE_VERSION} already exists. Please choose a different release version or delete the existing tag before running this script."
  exit 1
fi
echo "✅ Tag v${RELEASE_VERSION} does not already exist."

# Verify that the release version is not already in use in the pom.xml files
for pom in pom.xml api/pom.xml cli/pom.xml maven-plugin/pom.xml; do
  pom_version=$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version -f "${pom}")
  if [[ "${pom_version}" == "${RELEASE_VERSION}" ]]; then
    echo "Error: Release version ${RELEASE_VERSION} is already set in ${pom}. Please choose a different release version or update the pom.xml files before running this script."
    exit 1
  fi
  if [[ "${pom_version}" == "${NEXT_DEV_VERSION}" ]]; then
    echo "Error: New development version ${NEXT_DEV_VERSION} is already set in ${pom}. Please choose a different new development version or update the pom.xml files before running this script."
    exit 1
  fi
done
echo "✅ Release and development versions are not already in use in pom.xml files."

# Verify that the release version is not set in the CHANGELOG.md file
if grep -q "## \[${RELEASE_VERSION}\]" CHANGELOG.md || grep -q "## \[v${RELEASE_VERSION}\]" CHANGELOG.md; then
  echo "Error: Release version ${RELEASE_VERSION} is already in use in the CHANGELOG.md file. Please choose a different release version or update the CHANGELOG.md file before running this script."
  exit 1
fi
echo "✅ Release version ${RELEASE_VERSION} is not already in CHANGELOG.md."

# Verify that there is an unreleased section in the CHANGELOG.md file
if ! grep -q "## \[Unreleased\]" CHANGELOG.md; then
  echo "Error: No unreleased section found in CHANGELOG.md file. Please add an unreleased section before running this script."
  exit 1
fi
echo "✅ CHANGELOG.md has an [Unreleased] section."

# Verify that the unreleased section has content
UNRELEASED_CONTENT=$(sed -n '/## \[Unreleased\]/,/## \[/{/## \[/!p;}' CHANGELOG.md | grep -v '^[[:space:]]*$' || true)
if [[ -z "${UNRELEASED_CONTENT}" ]]; then
  echo "Error: The [Unreleased] section in CHANGELOG.md has no content. Please add changelog entries before releasing."
  exit 1
fi
echo "✅ [Unreleased] section in CHANGELOG.md has content."

# Verify that the SBOM scanning tools are available (matches release.yml)
for cmd in trivy cdxgen cyclonedx; do
  if ! command -v "$cmd" &>/dev/null; then
    echo "Error: Required command '$cmd' is not installed (needed for the SBOM vulnerability scan)."
    exit 1
  fi
done
echo "✅ SBOM scanning tools (trivy, cdxgen, cyclonedx) are available."

# Generate the aggregate SBOM and scan it for known HIGH/CRITICAL vulnerabilities,
# mirroring the "Scan SBOM for vulnerabilities" step in .github/workflows/release.yml
# so issues surface here instead of failing the release pipeline.
echo "Generating aggregate SBOM (this may take a few minutes)..."
./mvnw -ntp -P generate-sbom -DskipTests package
if [[ ! -f target/bom_all.json ]]; then
  echo "Error: Aggregate SBOM (target/bom_all.json) was not generated."
  exit 1
fi
echo "Scanning aggregate SBOM for HIGH/CRITICAL vulnerabilities with Trivy..."
trivy sbom --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 --format table target/bom_all.json
echo "✅ No HIGH/CRITICAL vulnerabilities found in aggregate SBOM."

# Confirm before proceeding (unless dry-run)
if ! "${DRY_RUN}"; then
  echo "About to release ${RELEASE_VERSION} and start development of ${NEXT_DEV_VERSION}."
  read -rp "Continue? [y/N] " confirm
  if [[ ! "${confirm}" =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 0
  fi
fi

run() {
  if "${DRY_RUN}"; then
    echo "[DRY RUN] $*"
  else
    "$@"
  fi
}

sed_inplace() {
  local file="${!#}"
  local tmp
  tmp=$(mktemp)
  sed "$@" > "$tmp" && mv "$tmp" "$file"
}

# Rename the unreleased section in the CHANGELOG.md file to the release version with today's date
RELEASE_DATE=$(date +%Y-%m-%d)
run sed_inplace "s/## \[Unreleased\]/## [v${RELEASE_VERSION}] - ${RELEASE_DATE}/g" CHANGELOG.md

run ./mvnw versions:set -DnewVersion="${RELEASE_VERSION}" -DgenerateBackupPoms=false -f pom.xml
run git add CHANGELOG.md api/pom.xml cli/pom.xml maven-plugin/pom.xml pom.xml
run git commit -s -S -m "Release version ${RELEASE_VERSION}"
run git tag -s "v${RELEASE_VERSION}" -m "Release version ${RELEASE_VERSION}"
run git push origin "v${RELEASE_VERSION}"
run git push origin main

echo "Release ${RELEASE_VERSION} created and pushed to origin."

# Add unreleased section in the CHANGELOG.md file as the first h2 in the file.
# There is always a h1 at the top of the file, so it needs to be added after the first h1 and its content.
run sed_inplace "0,/## /s/## /## [Unreleased]\n\n## /" CHANGELOG.md

# Set the new development version in the pom.xml files and commit the changes
run ./mvnw versions:set -DnewVersion="${NEXT_DEV_VERSION}" -DgenerateBackupPoms=false -f pom.xml
run git add CHANGELOG.md api/pom.xml cli/pom.xml maven-plugin/pom.xml pom.xml
run git commit -s -S -m "Start development of version ${NEXT_DEV_VERSION}"
run git push origin main
