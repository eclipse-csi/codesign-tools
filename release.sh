#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 [--dry-run] <RELEASE_VERSION>"
  echo
  echo "Arguments:"
  echo "  RELEASE_VERSION   The version to release (e.g., 0.5.12)"
  echo
  echo "Options:"
  echo "  --dry-run         Show what would be done without making any changes"
  echo
  echo "The project version is derived from git tags (.mvn/maven-git-versioning-extension.xml),"
  echo "so releasing is a CHANGELOG.md commit plus a signed vRELEASE_VERSION tag. The POMs are"
  echo "not edited, and there is no next-development-version commit: every commit after the"
  echo "tag builds as the next patch version with -SNAPSHOT."
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

if [[ $# -ne 1 ]]; then
  echo "Error: Expected 1 argument, got $#"
  usage
fi

RELEASE_VERSION="$1"

if "${DRY_RUN}"; then
  echo "[DRY RUN] No changes will be made."
  echo
fi

# Verify that required tools are available
for cmd in git sed python3; do
  if ! command -v "$cmd" &>/dev/null; then
    echo "Error: Required command '$cmd' is not installed."
    exit 1
  fi
done
echo "✅ Required tools (git, sed, python3) are available."

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

# Verify that commit/tag signing is properly configured.
# The release commit and tag are signed (git commit -S / git tag -s), which
# delegates to git's configured signing backend. Honor gpg.format so both
# OpenPGP (default) and SSH signing (e.g. Secretive / 1Password) work.
SIGNING_KEY=$(git config --get user.signingkey 2>/dev/null || true)
if [[ -z "${SIGNING_KEY}" ]]; then
  echo "Error: No signing key configured. Please set one with: git config --global user.signingkey <KEY>"
  exit 1
fi
SIGNING_FORMAT=$(git config --get gpg.format 2>/dev/null || echo "openpgp")
case "${SIGNING_FORMAT}" in
  ssh)
    # SSH signing delegates to gpg.ssh.program (defaults to ssh-keygen).
    SSH_SIGN_PROGRAM=$(git config --get gpg.ssh.program 2>/dev/null || echo "ssh-keygen")
    if ! command -v "${SSH_SIGN_PROGRAM%% *}" &>/dev/null; then
      echo "Error: SSH signing program '${SSH_SIGN_PROGRAM}' is not installed (needed for gpg.format=ssh)."
      exit 1
    fi
    # When user.signingkey is a key file path, make sure the file exists.
    resolved_key="${SIGNING_KEY/#\~/${HOME}}"
    if [[ "${resolved_key}" == /* || "${resolved_key}" == ./* ]] && [[ ! -f "${resolved_key}" ]]; then
      echo "Error: SSH signing key file '${SIGNING_KEY}' does not exist."
      exit 1
    fi
    echo "✅ SSH signing key (${SIGNING_KEY}) is configured."
    ;;
  openpgp|"")
    if ! command -v gpg &>/dev/null; then
      echo "Error: Required command 'gpg' is not installed (needed for OpenPGP signing)."
      exit 1
    fi
    if ! gpg --list-secret-keys "${SIGNING_KEY}" &>/dev/null; then
      echo "Error: GPG secret key '${SIGNING_KEY}' is not available. Please ensure the key is imported and accessible."
      exit 1
    fi
    echo "✅ GPG signing key (${SIGNING_KEY}) is configured and available."
    ;;
  *)
    echo "Error: Unsupported gpg.format '${SIGNING_FORMAT}'. Supported values: openpgp, ssh."
    exit 1
    ;;
esac

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

# Verify that the local main branch is up to date (or ahead without diverging) with origin/main.
# --tags: the release tags are the version history, so the checks below must see all of them.
git fetch -q --tags origin main
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

# Verify that the tag for the release version does not already exist
if git rev-parse "v${RELEASE_VERSION}" >/dev/null 2>&1; then
  echo "Error: Tag v${RELEASE_VERSION} already exists. Please choose a different release version or delete the existing tag before running this script."
  exit 1
fi
echo "✅ Tag v${RELEASE_VERSION} does not already exist."

# Verify that HEAD is not already a release. Tagging it a second time would give one commit two
# versions, and the build would pick whichever tag the extension sees first.
HEAD_RELEASE_TAGS=$(git tag --points-at HEAD --list 'v[0-9]*')
if [[ -n "${HEAD_RELEASE_TAGS}" ]]; then
  echo "Error: HEAD is already tagged as a release (${HEAD_RELEASE_TAGS//$'\n'/, }). Commit something before releasing again."
  exit 1
fi
echo "✅ HEAD is not already tagged as a release."

# Verify that the release version is greater than every version released so far. The project
# version is derived from the tags, so they are the only record of what has been released.
LATEST_VERSION=$(git tag --list 'v[0-9]*' | sed 's/^v//' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1 || true)
if [[ -n "${LATEST_VERSION}" ]] \
   && { [[ "${RELEASE_VERSION}" == "${LATEST_VERSION}" ]] \
        || [[ "$(printf '%s\n' "${LATEST_VERSION}" "${RELEASE_VERSION}" | sort -V | tail -1)" != "${RELEASE_VERSION}" ]]; }; then
  echo "Error: Release version (${RELEASE_VERSION}) must be greater than the latest release (${LATEST_VERSION})."
  exit 1
fi
echo "✅ Release version (${RELEASE_VERSION}) is greater than the latest release (${LATEST_VERSION:-none})."

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

# Verify that the [Unreleased] section renders as release notes. The GitHub release body is
# produced from it by .github/scripts/render-release-notes.py, so a problem here would
# otherwise only surface in CI, after the tag has already been pushed. GPG_KEY_ID must match
# the repository secret of the same name: it is the artifact signing key advertised to users
# in the notes, which is not necessarily the key git signs commits with.
if [[ -z "${GPG_KEY_ID:-}" ]]; then
  echo "Error: GPG_KEY_ID is not set. Export the OpenPGP key that signs the release artifacts (same value as the GPG_KEY_ID repository secret) before running this script."
  exit 1
fi
if ! python3 .github/scripts/render-release-notes.py --unreleased --version "${RELEASE_VERSION}" --check; then
  echo "Error: Could not render release notes from the [Unreleased] section of CHANGELOG.md."
  exit 1
fi
echo "✅ Release notes render from the [Unreleased] section."

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
  echo "About to release ${RELEASE_VERSION}."
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

# Open a new, empty unreleased section as the first h2 in the file, in the same commit.
# There is always a h1 at the top of the file, so it needs to be added after the first h1 and its content.
run sed_inplace "0,/## /s/## /## [Unreleased]\n\n## /" CHANGELOG.md

# The POMs are not touched: the tag alone sets the version (.mvn/maven-git-versioning-extension.xml).
run git add CHANGELOG.md
run git commit -s -S -m "Release version ${RELEASE_VERSION}"
run git tag -s "v${RELEASE_VERSION}" -m "Release version ${RELEASE_VERSION}"

# Before pushing, check that the build derives the release version from the new tag. The
# Release workflow builds and publishes whatever version the extension computes, so a tag the
# extension does not recognise would otherwise only show up there, as a SNAPSHOT release.
if ! "${DRY_RUN}"; then
  TAGGED_VERSION=$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version | tail -n 1)
  if [[ "${TAGGED_VERSION}" != "${RELEASE_VERSION}" ]]; then
    echo "Error: The build derives version '${TAGGED_VERSION}' from tag v${RELEASE_VERSION}, expected ${RELEASE_VERSION}."
    echo "Nothing was pushed. To undo: git tag -d v${RELEASE_VERSION} && git reset --hard HEAD~1"
    exit 1
  fi
  echo "✅ The build derives version ${TAGGED_VERSION} from tag v${RELEASE_VERSION}."
fi

# Push main first, then the tag, so the Release workflow starts once the release commit is
# already on main.
run git push origin main
run git push origin "v${RELEASE_VERSION}"

echo "Release ${RELEASE_VERSION} created and pushed to origin."
