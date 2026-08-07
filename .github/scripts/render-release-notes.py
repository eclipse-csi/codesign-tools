#!/usr/bin/env python3
"""
Render the GitHub release body for a version from CHANGELOG.md and git history.

The body is assembled from four parts:

1. The curated Keep a Changelog section for the version. This is the headline content and
   the reason the script exists: JReleaser used to generate the body from raw commits, which
   buried the hand-written notes and surfaced a list of Dependabot bumps instead.
2. A collapsed <details> block listing every commit since the previous tag, split into
   dependency updates and everything else.
3. The human contributors for that range (bots filtered out).
4. Artifact verification instructions and a tag-to-tag compare link.

The release workflow writes the result to target/release-notes.md, which JReleaser publishes
verbatim through <changelog><external> in pom.xml. The same script backfills the bodies of
already-published releases with --publish.
"""

import argparse
import difflib
import os
import re
import subprocess
import sys
from pathlib import Path

REPO = "eclipse-csi/codesign-tools"
CHANGELOG = Path("CHANGELOG.md")

# Matches "## [Unreleased]" and "## [v0.8.2] - 2026-08-06". release.sh checks for both the
# "[v0.8.2]" and "[0.8.2]" spellings, so both are accepted here.
SECTION_RE = re.compile(r"^## \[(?P<label>[^\]]+)\]\s*(?:-\s*(?P<date>\S+))?\s*$")

# Commits release.sh generates for version bookkeeping. They carry no information for a
# reader of the release notes, and are the only non-conventional subjects in the history.
MECHANICS_RE = re.compile(r"^(?:Release version |Start development of version )")

DEPENDENCY_RE = re.compile(r"^(?:build|ci|chore)\((?:deps|deps-dev)\)")

PR_SUFFIX_RE = re.compile(r"\s*\(#\d+\)\s*$")

# GitHub release bodies are truncated by JReleaser at 10,000 characters, with the remainder
# attached as a stray RELEASE.md asset. Fail before that happens.
MAX_BODY = 9_500

# The OpenPGP key that signs the release artifacts. Not hardcoded: the workflow passes the
# GPG_KEY_ID repository secret, the same value JReleaser uses as JRELEASER_GPG_KEYNAME, so
# the key advertised to users cannot drift from the key that actually signed the assets.
SIGNING_KEY_ENV = "GPG_KEY_ID"

MAVEN_ARTIFACTS = [
    ("API", "codesign-api"),
    ("Maven plugin", "codesign-maven-plugin"),
    ("CLI", "codesign-cli"),
]


def git(*args):
    """Run a git command and return its stdout, or None if it failed."""
    result = subprocess.run(["git", *args], capture_output=True, text=True, check=False)
    if result.returncode != 0:
        return None
    return result.stdout.strip()


def normalize(version):
    return version.lstrip("vV")


def project_version():
    """The version in the root POM, without its -SNAPSHOT suffix.

    Only used by --unreleased, where there is no tag to take the version from.
    """
    text = Path("pom.xml").read_text(encoding="utf-8")
    match = re.search(
        r"<artifactId>codesign-parent</artifactId>\s*<version>([^<]+)</version>", text
    )
    if not match:
        sys.exit("error: could not read the project version from pom.xml")
    return match.group(1).removesuffix("-SNAPSHOT")


def sections(changelog):
    """Return [(normalized_label, body)] for every '## [...]' section, in file order."""
    if not changelog.exists():
        sys.exit(f"error: {changelog} not found (run from the repository root)")

    lines = changelog.read_text(encoding="utf-8").splitlines()
    starts = []
    for index, line in enumerate(lines):
        match = SECTION_RE.match(line)
        if match:
            starts.append((normalize(match.group("label")).lower(), index))

    result = []
    for position, (label, index) in enumerate(starts):
        end = starts[position + 1][1] if position + 1 < len(starts) else len(lines)
        result.append((label, "\n".join(lines[index + 1 : end]).strip()))
    return result


def curated_section(changelog, label):
    """Return the body of the CHANGELOG section matching `label`.

    `label` is either "Unreleased" or a version, compared without its leading "v".
    """
    wanted = normalize(label).lower()
    for found, body in sections(changelog):
        if found != wanted:
            continue
        if not body:
            sys.exit(f"error: the '## [{label}]' section in {changelog} is empty")
        return body

    sys.exit(
        f"error: no '## [{label}]' section in {changelog}. "
        "Add one before releasing, or check the version spelling."
    )


def previous_tag(changelog, label, head):
    """Return the tag the release should be compared against.

    Prefer the version documented directly below `label` in CHANGELOG.md. That is the last
    version that actually shipped, whereas the nearest git tag may belong to a release that
    was tagged but never published — v0.8.0 is such a tag, and using it would have shown 3
    commits for v0.8.1 instead of the 32 its changelog describes. Fall back to the nearest
    tag when the changelog cannot answer (oldest section, or a version with no tag).
    """
    labels = [found for found, _ in sections(changelog)]
    wanted = normalize(label).lower()
    if wanted in labels:
        position = labels.index(wanted) + 1
        if position < len(labels):
            candidate = f"v{labels[position]}"
            if git("rev-parse", "--verify", f"{candidate}^{{commit}}"):
                return candidate

    return git("describe", "--tags", "--abbrev=0", f"{head}^")


def commit_list(rev_range):
    """Return (dependency_commits, other_commits) as "hash subject" strings."""
    output = git("log", "--no-merges", "--format=%h %s", rev_range)
    if not output:
        return [], []

    dependencies, others = [], []
    for line in output.splitlines():
        commit, _, subject = line.partition(" ")
        if MECHANICS_RE.match(subject):
            continue
        # Drop the squash-merge "(#91)" suffix. JReleaser's release.github.issues feature
        # comments on and labels every issue referenced in the body, and these are merged
        # Dependabot PRs -- announcing them as "resolved in v0.8.3" is both noisy and wrong.
        # References written deliberately in the CHANGELOG prose are left untouched.
        entry = f"{commit} {PR_SUFFIX_RE.sub('', subject)}"
        (dependencies if DEPENDENCY_RE.match(subject) else others).append(entry)
    return dependencies, others


def contributors(rev_range):
    """Return the sorted unique human authors in the range."""
    output = git("log", "--format=%aN", rev_range)
    if not output:
        return []
    names = {name for name in output.splitlines() if "[bot]" not in name}
    return sorted(names)


def render_commits(dependencies, others, previous):
    total = len(dependencies) + len(others)
    if not total:
        return ""

    blocks = []
    if others:
        heading = "**Changes**\n\n" if dependencies else ""
        blocks.append(heading + "\n".join(f"- {line}" for line in others))
    if dependencies:
        heading = "**Dependency updates**\n\n" if others else ""
        blocks.append(heading + "\n".join(f"- {line}" for line in dependencies))

    # "N commits since <tag>" rather than "all N commits": the release-mechanics commits are
    # filtered out, so N is not the raw count of the range.
    noun = "commit" if total == 1 else "commits"
    return (
        f"<details>\n<summary>{total} {noun} since {previous}</summary>\n\n"
        + "\n\n".join(blocks)
        + "\n\n</details>"
    )


def signing_key():
    """The key users should import to check the signatures, from the environment."""
    key = os.environ.get(SIGNING_KEY_ENV, "").strip()
    if not key:
        sys.exit(
            f"error: {SIGNING_KEY_ENV} is not set. It must hold the OpenPGP key that signs "
            "the release artifacts, i.e. the same value as the GPG_KEY_ID repository "
            f"secret. Export it before rendering: export {SIGNING_KEY_ENV}=<fingerprint>"
        )
    return key


def render_verification(version):
    coordinates = "\n".join(
        f"| {label} | `org.eclipse.csi:{artifact}:{version}` |"
        for label, artifact in MAVEN_ARTIFACTS
    )
    # Only the CLI fat JAR carries a build attestation: the archives are assembled in a job
    # that does not run actions/attest-build-provenance. Claiming otherwise would send users
    # to a 'gh attestation verify' that fails with 404.
    return f"""## Verifying this release

Every asset has a detached OpenPGP signature (`.asc`) made by the Eclipse CSI project key,
and `checksums_sha256.txt` covers all of them.

```shell
# 1. Import the signing key, then verify the checksum manifest
gpg --keyserver hkps://keyserver.ubuntu.com --recv-keys {signing_key()}
gpg --verify checksums_sha256.txt.asc checksums_sha256.txt

# 2. Verify what you downloaded against the manifest
sha256sum --ignore-missing -c checksums_sha256.txt   # Linux
shasum -a 256 --ignore-missing -c checksums_sha256.txt   # macOS

# 3. Verify the build provenance of the CLI fat JAR
gh attestation verify codesign-cli-{version}-bin.jar --repo {REPO}
```

The key is fetched above by the identifier published here, so `gpg --verify` should report
`Good signature from "Eclipse Common Security Infrastructure Project <csi-dev@eclipse.org>"`.
It also warns that the key is not certified by you, which is expected unless you have signed
it yourself.

### Maven Central

| Artifact | Coordinates |
| --- | --- |
{coordinates}"""


def render(label, tag, head, override=None):
    """Assemble the full release body.

    `label` selects the CHANGELOG section, `tag` names the release (None when previewing an
    unreleased version), `head` is the git revision the release points at, and `override`
    forces the tag to compare against.
    """
    previous = override or previous_tag(CHANGELOG, label, head)
    if override and not git("rev-parse", "--verify", f"{override}^{{commit}}"):
        sys.exit(f"error: --previous tag {override} not found")

    parts = ["## What's Changed", "", curated_section(CHANGELOG, label)]

    # Without a previous tag there is no meaningful range: listing the entire history would
    # be worse than listing nothing.
    if previous:
        rev_range = f"{previous}..{head}"
        commits = render_commits(*commit_list(rev_range), previous)
        if commits:
            parts += ["", commits]

        people = contributors(rev_range)
        if people:
            parts += ["", "## Contributors", ""]
            parts += [f"- {name}" for name in people]

    version = normalize(tag) if tag else project_version()
    parts += ["", render_verification(version)]

    if previous and tag:
        parts += [
            "",
            f"**Full changelog**: https://github.com/{REPO}/compare/{previous}...{tag}",
        ]

    body = "\n".join(parts).strip() + "\n"
    if len(body) > MAX_BODY:
        sys.exit(
            f"error: the release body is {len(body)} characters. JReleaser truncates at "
            "10,000 and attaches the remainder as a stray RELEASE.md asset. Shorten the "
            "CHANGELOG section, or pass a nearer --previous."
        )
    return body


def publish(tag, body, dry_run):
    """Replace the body of an existing GitHub release, or show what would change."""
    current = subprocess.run(
        ["gh", "release", "view", tag, "--repo", REPO, "--json", "body", "-q", ".body"],
        capture_output=True,
        text=True,
        check=False,
    )
    if current.returncode != 0:
        sys.exit(f"error: could not read release {tag}: {current.stderr.strip()}")

    if current.stdout.strip() == body.strip():
        print(f"{tag}: already up to date", file=sys.stderr)
        return

    if dry_run:
        diff = difflib.unified_diff(
            current.stdout.splitlines(keepends=True),
            body.splitlines(keepends=True),
            fromfile=f"{tag} (published)",
            tofile=f"{tag} (rendered)",
        )
        sys.stdout.writelines(diff)
        return

    subprocess.run(
        ["gh", "release", "edit", tag, "--repo", REPO, "--notes-file", "-"],
        input=body,
        text=True,
        check=True,
    )
    print(f"{tag}: release notes updated", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--tag", help="release tag to render, e.g. v0.8.2")
    target.add_argument(
        "--unreleased",
        action="store_true",
        help="render the [Unreleased] section against HEAD (local preview)",
    )
    parser.add_argument(
        "--previous",
        metavar="TAG",
        help="tag to compare against, overriding auto-detection. Needed when backfilling "
        "a release whose predecessor in CHANGELOG.md was tagged but never published.",
    )
    parser.add_argument("--output", type=Path, help="write here instead of stdout")
    parser.add_argument(
        "--check",
        action="store_true",
        help="render and validate, then discard the result",
    )
    parser.add_argument(
        "--publish",
        action="store_true",
        help="replace the body of the existing GitHub release with 'gh release edit'",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="with --publish, diff against the live body instead of replacing it",
    )
    args = parser.parse_args()

    if (args.publish or args.dry_run) and not args.tag:
        parser.error("--publish and --dry-run require --tag")

    if args.unreleased:
        body = render("Unreleased", None, "HEAD", args.previous)
    else:
        if git("rev-parse", "--verify", f"{args.tag}^{{commit}}") is None:
            sys.exit(
                f"error: tag {args.tag} not found. Fetch tags with 'git fetch --tags'."
            )
        body = render(args.tag, args.tag, args.tag, args.previous)

    if args.publish or args.dry_run:
        publish(args.tag, body, args.dry_run)
    elif args.check:
        print(f"release notes render cleanly ({len(body)} characters)", file=sys.stderr)
    elif args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(body, encoding="utf-8")
    else:
        sys.stdout.write(body)


if __name__ == "__main__":
    main()
