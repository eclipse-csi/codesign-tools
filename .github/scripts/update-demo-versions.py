#!/usr/bin/env python3
"""Point the demos at a new release of the project.

The demos pin the released CLI, Maven plugin and actions, so that they run what users get.
The Release workflow runs this script once the release is published, with the release
version and the commit of its tag, and opens a pull request with the result.

Usage: update-demo-versions.py --version X.Y.Z --commit SHA [--github-output FILE]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEMO = ROOT / "demo"
SUFFIXES = {".md", ".sh", ".xml", ".yml", ".yaml"}

VERSION = r"\d+\.\d+\.\d+"
VERSION_RE = re.compile(rf"^{VERSION}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")

# Each pattern captures the text around a pinned version in the group "version", and, for
# the actions, the pinned commit in the group "commit".
PINS = [
    # CSI_CODESIGN_VERSION: X.Y.Z (workflows)
    re.compile(rf"(?<=CSI_CODESIGN_VERSION: )(?P<version>{VERSION})\b"),
    # CODESIGN_VERSION=X.Y.Z and ${CODESIGN_VERSION:-X.Y.Z} (shell scripts)
    re.compile(rf"(?<=\bCODESIGN_VERSION)(?P<sep>=|:-)(?P<version>{VERSION})\b"),
    # <codesign-maven-plugin.version>X.Y.Z</codesign-maven-plugin.version>
    re.compile(rf"(?<=<codesign-maven-plugin\.version>)(?P<version>{VERSION})(?=<)"),
    # org.eclipse.csi:codesign-maven-plugin:X.Y.Z:codesign
    re.compile(
        rf"(?<=org\.eclipse\.csi:codesign-maven-plugin:)(?P<version>{VERSION})(?=:)"
    ),
    # codesign-cli-X.Y.Z-bin.jar
    re.compile(rf"(?<=codesign-cli-)(?P<version>{VERSION})(?=-bin\.jar)"),
    # uses: eclipse-csi/codesign-tools/<path>@<commit> # vX.Y.Z
    re.compile(
        rf"(?<=eclipse-csi/codesign-tools/)(?P<path>[^@\s]+)@(?P<commit>[0-9a-f]{{40}})"
        rf" # v(?P<version>{VERSION})\b"
    ),
]


def parse(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def update(text: str, version: str, commit: str) -> str:
    def replace(match: re.Match[str]) -> str:
        groups = match.groupdict()
        # Never move a pin backwards, e.g. when an older release is published again.
        if parse(groups["version"]) >= parse(version):
            return match.group(0)
        if "commit" in groups:
            return f"{groups['path']}@{commit} # v{version}"
        return f"{groups.get('sep') or ''}{version}"

    for pin in PINS:
        text = pin.sub(replace, text)
    return text


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--version", required=True, help="the release version, X.Y.Z")
    parser.add_argument(
        "--commit", required=True, help="the full SHA of the release commit"
    )
    parser.add_argument("--github-output", help="file to write changed=true|false to")
    args = parser.parse_args()

    if not VERSION_RE.match(args.version):
        print(f"Invalid version {args.version!r}: expected X.Y.Z.", file=sys.stderr)
        return 2
    if not COMMIT_RE.match(args.commit):
        print(f"Invalid commit {args.commit!r}: expected a full SHA.", file=sys.stderr)
        return 2

    changed = []
    for path in sorted(DEMO.rglob("*")):
        if not path.is_file() or path.suffix not in SUFFIXES:
            continue
        text = path.read_text(encoding="utf-8")
        updated = update(text, args.version, args.commit)
        if updated != text:
            path.write_text(updated, encoding="utf-8")
            changed.append(path.relative_to(ROOT))

    for path in changed:
        print(f"Updated {path}")
    if not changed:
        print(f"The demos already use {args.version} or later.")

    if args.github_output:
        with open(args.github_output, "a", encoding="utf-8") as output:
            output.write(f"changed={'true' if changed else 'false'}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
