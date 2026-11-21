#!/usr/bin/env python3
"""
Publish the staged Maven artifacts to Maven Central, safely on a re-run or a fresh run.

`jreleaser:deploy` on its own uploads a new bundle on every run. Running it again for a version
that a previous run got further with than its job did (validation slower than the job timeout,
a publication that succeeded after the job was killed, a failure later in the workflow) then
fails, one of two ways:

- "Some artifacts were already deployed to the target staging repository": JReleaser found the
  components already published, so there was nothing left to do;
- "Could not publish deployment ...: 400": the earlier deployment was still pending, a second
  one for the same version was uploaded, and Central refused to publish it. It stays behind in
  the Central Portal and has to be dropped by hand.

This script owns the state transitions instead, and converges on the same end state however
many times it runs, whether as a re-run attempt or as a new run for the same tag:

1. The components found in the staging directories are checked on Central. If all of them are
   published, their jars and POMs are downloaded from the Maven Central repository and compared
   with the staged ones: identical, there is nothing to do; different, Central holds something
   this commit does not build, which needs a human. Some components but not all published:
   stop, as that needs a human too.
2. A deployment an earlier run or attempt uploaded is recorded in a commit status on the commit
   being released (context "Maven Central (<version>)"), with the SHA-256 digest of the jars and
   POMs it was built from. The Publisher API cannot list deployments, and workflow artifacts do
   not outlive their run, so this is what lets any later run find it. If its files are the
   staged ones: still validating or publishing, wait for it; validated, publish it; failed, drop
   it and upload again. If they are not: drop it once Central allows it and upload again, or
   stop if it is already being published.
3. Otherwise the upload command (`jreleaser:deploy` with the UPLOAD stage, which builds the
   bundle, applies the Maven Central rules and waits for validation) runs. The deployment ID is
   read from its output and recorded in the commit status as soon as it is printed, so that a
   later run finds it even if this one is killed.
4. The validated deployment is published and followed until it is PUBLISHED. If Central
   refuses it, or it fails, while the components became published in the meantime (by a
   deployment that was never recorded), the published files are compared as in 1.

Only jars and POMs are compared. They are reproducible: a rebuild of the same commit produces
the same bytes. Signatures, Sigstore bundles and SBOMs are not, so they are expected to differ
between builds of the same commit.

The Central Portal Publisher API is documented at
https://central.sonatype.org/publish/publish-portal-api/. The /published endpoint is not, but it
is the one JReleaser itself uses to refuse an upload of already-published components.
"""

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

DEFAULT_API_URL = "https://central.sonatype.com/api/v1/publisher"
DEFAULT_REPOSITORY_URL = "https://repo1.maven.org/maven2"
DEPLOYMENTS_PAGE = "https://central.sonatype.com/publishing/deployments"

# JReleaser logs "Bundle <file> uploaded as deployment <id>" right after the upload, before it
# starts waiting for validation (org.jreleaser.sdk.mavencentral.MavenCentral#upload).
DEPLOYMENT_ID_RE = re.compile(r"uploaded as deployment (?P<id>[0-9A-Za-z-]+)")

# The environment variable JReleaser reads the deployer stage from, for the deployer named
# "sonatype" in pom.xml. UPLOAD uploads the bundle and waits for validation, without publishing.
STAGE_ENV = "JRELEASER_DEPLOY_MAVEN_MAVENCENTRAL_SONATYPE_STAGE"

# The files whose content identifies a release. Everything else in a staging repository
# (signatures, checksums, Sigstore bundles, SBOMs) legitimately differs between builds.
PAYLOAD_SUFFIXES = (".jar", ".pom")

# The commit status description is limited to 140 characters by GitHub.
STATUS_DESCRIPTION_RE = re.compile(
    r"deployment=(?P<id>[0-9A-Za-z-]+) payload=sha256:(?P<digest>[0-9a-f]{64})"
)

# Recorded before uploading, so that a token unable to write commit statuses fails the job before
# anything reaches Central.
NO_DEPLOYMENT = "none"

PENDING_STATES = {"PENDING", "VALIDATING"}
DROPPABLE_STATES = {"VALIDATED", "FAILED"}
TRANSIENT_HTTP_CODES = {408, 429, 500, 502, 503, 504}
HTTP_ATTEMPTS = 6


class PublishError(Exception):
    pass


def log(message: str) -> None:
    print(message, flush=True)


def annotate(level: str, message: str) -> None:
    """Print a GitHub Actions annotation (::error::, ::warning::, ::notice::)."""
    # Workflow commands take a single line; %0A is their escaped newline.
    print(f"::{level}::" + message.replace("%", "%25").replace("\n", "%0A"), flush=True)


def http(
    method: str, url: str, headers: dict[str, str], body: bytes | None = None
) -> tuple[int, bytes]:
    """Send a request, retrying transient failures. Returns (status, body) for any other
    response, including client errors, which the callers interpret."""
    delay = 5
    for attempt in range(1, HTTP_ATTEMPTS + 1):
        request = urllib.request.Request(url, data=body, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as e:
            content = e.read()
            if e.code not in TRANSIENT_HTTP_CODES or attempt == HTTP_ATTEMPTS:
                return e.code, content
            reason = f"HTTP {e.code}"
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            if attempt == HTTP_ATTEMPTS:
                raise PublishError(f"{method} {url} failed: {e}") from e
            reason = str(e)
        log(
            f"  {method} {url}: {reason}, retrying in {delay}s ({attempt}/{HTTP_ATTEMPTS})"
        )
        time.sleep(delay)
        delay = min(delay * 2, 60)
    raise AssertionError("unreachable")


@dataclass(frozen=True, order=True)
class Component:
    group_id: str
    artifact_id: str
    version: str

    def __str__(self) -> str:
        return f"{self.group_id}:{self.artifact_id}:{self.version}"

    @property
    def purl(self) -> str:
        return f"pkg:maven/{self.group_id}/{self.artifact_id}@{self.version}"


class Staging:
    """The staged components, and the jars and POMs that identify them."""

    def __init__(self, staging_dirs: list[Path], version: str) -> None:
        components = set()
        # Repository path (group/artifact/version/file) -> (local file, SHA-256).
        self.payload: dict[str, tuple[Path, str]] = {}
        for staging_dir in staging_dirs:
            if not staging_dir.is_dir():
                raise PublishError(f"Staging directory {staging_dir} does not exist")
            for file in sorted(staging_dir.rglob("*")):
                if not file.is_file() or not file.name.endswith(PAYLOAD_SUFFIXES):
                    continue
                # <group path>/<artifactId>/<version>/<file>
                parts = file.relative_to(staging_dir).parts
                if len(parts) < 4:
                    continue
                if parts[-2] != version:
                    raise PublishError(
                        f"{file} is version {parts[-2]}, expected {version}"
                    )
                if file.suffix == ".pom":
                    components.add(
                        Component(".".join(parts[:-3]), parts[-3], parts[-2])
                    )
                self.payload["/".join(parts)] = (file, sha256_file(file))
        if not components:
            raise PublishError(
                "No POM found in the staging directories: nothing to publish"
            )
        self.components = sorted(components)
        manifest = "".join(
            f"{path} {digest}\n" for path, (_, digest) in sorted(self.payload.items())
        )
        self.digest = hashlib.sha256(manifest.encode()).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(1 << 16):
            digest.update(chunk)
    return digest.hexdigest()


class Central:
    def __init__(self, api_url: str, username: str, password: str) -> None:
        self.api_url = api_url.rstrip("/")
        token = base64.b64encode(f"{username}:{password}".encode()).decode()
        self.headers = {
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
        }

    def _request(
        self, method: str, path: str, query: dict | None = None
    ) -> tuple[int, str]:
        url = self.api_url + path
        if query:
            url += "?" + urllib.parse.urlencode(query)
        status, body = http(method, url, self.headers)
        return status, body.decode("utf-8", "replace")

    def is_published(self, component: Component) -> bool:
        status, body = self._request(
            "GET",
            "/published",
            {
                "namespace": component.group_id,
                "name": component.artifact_id,
                "version": component.version,
            },
        )
        if status != 200:
            raise PublishError(
                f"Could not check whether {component} is published: HTTP {status}: {body}"
            )
        return bool(json.loads(body).get("published"))

    def status(self, deployment_id: str) -> dict | None:
        """The deployment's status document, or None if Central does not know the ID (it
        deletes deployments after 90 days)."""
        status, body = self._request("POST", "/status", {"id": deployment_id})
        if status == 404:
            return None
        if status != 200:
            raise PublishError(
                f"Could not get the status of deployment {deployment_id}: HTTP {status}: {body}"
            )
        return json.loads(body)

    def publish(self, deployment_id: str) -> tuple[int, str]:
        return self._request("POST", f"/deployment/{deployment_id}")

    def drop(self, deployment_id: str) -> None:
        status, body = self._request("DELETE", f"/deployment/{deployment_id}")
        if status not in (200, 204, 404):
            raise PublishError(
                f"Could not drop deployment {deployment_id}: HTTP {status}: {body}"
            )


class CommitStatus:
    """The record of the deployment made for this version, in a commit status of the commit
    being released, where every run and attempt for that commit can read it."""

    def __init__(
        self, api_url: str, repository: str, sha: str, token: str, version: str
    ):
        self.statuses_url = f"{api_url.rstrip('/')}/repos/{repository}/statuses/{sha}"
        self.list_url = f"{api_url.rstrip('/')}/repos/{repository}/commits/{sha}/statuses?per_page=100"
        self.context = f"Maven Central ({version})"
        self.headers = {
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }

    def load(self) -> tuple[str, str] | None:
        """(deployment ID, payload digest) of the latest record, if any."""
        status, body = http("GET", self.list_url, self.headers)
        if status != 200:
            raise PublishError(
                f"Could not read the commit statuses: HTTP {status}: {body.decode(errors='replace')}"
            )
        # Newest first.
        for entry in json.loads(body):
            if entry.get("context") == self.context:
                match = STATUS_DESCRIPTION_RE.fullmatch(entry.get("description") or "")
                if not match:
                    annotate(
                        "warning",
                        f"Ignoring the '{self.context}' commit status, which does not record a "
                        f"deployment: {entry.get('description')!r}",
                    )
                    return None
                if match.group("id") == NO_DEPLOYMENT:
                    return None
                return match.group("id"), match.group("digest")
        return None

    def save(self, state: str, deployment_id: str, digest: str) -> None:
        payload = {
            "state": state,
            "context": self.context,
            "description": f"deployment={deployment_id} payload=sha256:{digest}",
            "target_url": DEPLOYMENTS_PAGE,
        }
        status, body = http(
            "POST",
            self.statuses_url,
            {**self.headers, "Content-Type": "application/json"},
            json.dumps(payload).encode(),
        )
        if status != 201:
            raise PublishError(
                f"Could not write the '{self.context}' commit status (the job needs the "
                f"statuses: write permission): HTTP {status}: {body.decode(errors='replace')}"
            )

    def save_or_warn(self, state: str, deployment_id: str, digest: str) -> None:
        """Once a deployment exists, failing to record it must not fail a publication that
        works: only a later run would miss the record."""
        try:
            self.save(state, deployment_id, digest)
        except PublishError as e:
            annotate(
                "warning", f"Deployment {deployment_id} could not be recorded: {e}"
            )


class Deadline:
    def __init__(self, minutes: float) -> None:
        self.end = time.monotonic() + minutes * 60

    def remaining(self) -> float:
        return self.end - time.monotonic()


@dataclass
class Context:
    central: Central
    record: CommitStatus
    staging: Staging
    repository_url: str
    deadline: Deadline
    poll_seconds: float

    def sleep(self) -> None:
        time.sleep(min(self.poll_seconds, max(self.deadline.remaining(), 1)))

    def resume_hint(self) -> str:
        return (
            f"The deployment is recorded in the '{self.record.context}' commit status: re-run the "
            f"job, or run the workflow again for this tag, to carry on from where it is. "
            f"See {DEPLOYMENTS_PAGE}"
        )


def errors_of(deployment: dict) -> str:
    errors = deployment.get("errors") or {}
    if not errors:
        return "(Central reported no error details)"
    lines = []
    for subject, messages in errors.items():
        for message in messages if isinstance(messages, list) else [messages]:
            lines.append(f"  {subject}: {message}")
    return "\n".join(lines)


def wait_for(ctx: Context, deployment_id: str, targets: set[str]) -> dict:
    last_state = None
    while True:
        deployment = ctx.central.status(deployment_id)
        if deployment is None:
            raise PublishError(f"Central no longer knows deployment {deployment_id}")
        state = deployment.get("deploymentState")
        if state != last_state:
            log(f"Deployment {deployment_id} is {state}")
            last_state = state
        if state in targets:
            return deployment
        if ctx.deadline.remaining() <= 0:
            raise TimeoutError(
                f"Deployment {deployment_id} is still {state} and the time allotted to this step "
                f"has run out. {ctx.resume_hint()}"
            )
        ctx.sleep()


def published_components(ctx: Context) -> list[Component]:
    return [c for c in ctx.staging.components if ctx.central.is_published(c)]


def verify_published(ctx: Context) -> None:
    """Check that the Maven Central repository serves the staged jars and POMs, byte for byte.
    Files not there yet (the repository can lag behind a publication) are waited for."""
    pending = dict(ctx.staging.payload)
    mismatches = []
    while True:
        for path, (_, expected) in list(pending.items()):
            status, content = http(
                "GET", f"{ctx.repository_url.rstrip('/')}/{path}", {}
            )
            if status == 404:
                continue
            if status != 200:
                raise PublishError(f"Could not download {path}: HTTP {status}")
            del pending[path]
            if hashlib.sha256(content).hexdigest() != expected:
                mismatches.append(path)
        if not pending:
            break
        if ctx.deadline.remaining() <= 0:
            raise TimeoutError(
                f"Maven Central reports the components as published, but its repository does "
                f"not serve these files yet: {', '.join(sorted(pending))}. Re-run the job later."
            )
        log(f"Waiting for {len(pending)} file(s) to appear in {ctx.repository_url}")
        ctx.sleep()
    if mismatches:
        raise PublishError(
            "Maven Central already has this version, but not the files this commit builds. "
            "It cannot be republished: check where the published files come from.\n"
            f"Different: {', '.join(sorted(mismatches))}"
        )
    log(
        f"Maven Central serves the {len(ctx.staging.payload)} staged jars and POMs, byte for byte"
    )


def upload(ctx: Context, command: list[str]) -> str:
    """Run the upload command, echoing its output, and return the deployment ID it printed,
    which is recorded as soon as it is seen."""
    env = dict(os.environ)
    env[STAGE_ENV] = "UPLOAD"
    log(f"Running {' '.join(command)} with {STAGE_ENV}=UPLOAD")
    deployment_id = None
    with subprocess.Popen(
        command,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        errors="replace",
    ) as process:
        assert process.stdout is not None
        for line in process.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            match = DEPLOYMENT_ID_RE.search(line)
            if match and deployment_id is None:
                deployment_id = match.group("id")
                ctx.record.save_or_warn("pending", deployment_id, ctx.staging.digest)
        returncode = process.wait()

    if deployment_id is None:
        raise PublishError(
            f"The upload command exited with {returncode} without reporting a deployment ID, "
            f"so nothing was uploaded"
        )
    if returncode != 0:
        # Validation can fail, or outlast JReleaser's own wait (retryDelay x maxRetries in
        # pom.xml). Either way the deployment exists, and its status says what happened.
        annotate(
            "warning",
            f"The upload command exited with {returncode} after uploading deployment "
            f"{deployment_id}; following the deployment's status directly",
        )
    return deployment_id


def adopt(ctx: Context, deployment_id: str, digest: str) -> str | None:
    """Decide what to do with the deployment an earlier run or attempt recorded. Returns its ID
    if it can be carried on with, None if a new upload is needed."""
    deployment = ctx.central.status(deployment_id)
    if deployment is None:
        log(
            f"Central does not know the recorded deployment {deployment_id}: uploading again"
        )
        return None
    state = deployment.get("deploymentState")
    expected_purls = {c.purl for c in ctx.staging.components}
    purls = set(deployment.get("purls") or [])
    if purls and purls != expected_purls:
        annotate(
            "warning",
            f"The recorded deployment {deployment_id} is not one of this version "
            f"({', '.join(sorted(purls))}); leaving it alone and uploading again",
        )
        return None

    if digest != ctx.staging.digest:
        log(
            f"The recorded deployment {deployment_id} ({state}) was built from other files"
        )
        if state in PENDING_STATES:
            state = wait_for(
                ctx, deployment_id, DROPPABLE_STATES | {"PUBLISHING", "PUBLISHED"}
            )["deploymentState"]
        if state not in DROPPABLE_STATES:
            raise PublishError(
                f"Deployment {deployment_id} is {state}, and was built from other files than "
                f"the staged ones, so Central will not have the files this commit builds. "
                f"See {DEPLOYMENTS_PAGE}"
            )
        log(f"Dropping deployment {deployment_id} and uploading again")
        ctx.central.drop(deployment_id)
        return None

    if state == "FAILED":
        log(f"The recorded deployment {deployment_id} failed:\n{errors_of(deployment)}")
        log("Dropping it and uploading again")
        ctx.central.drop(deployment_id)
        return None
    log(f"Carrying on with the recorded deployment {deployment_id}, which is {state}")
    return deployment_id


def published_by_another(ctx: Context, deployment_id: str, state: str) -> bool:
    """When our deployment cannot be published: whether that is because the components were
    published in the meantime by a deployment nobody recorded, with the files staged here."""
    if len(published_components(ctx)) != len(ctx.staging.components):
        return False
    log(f"Deployment {deployment_id} is {state}, but the components are now published")
    verify_published(ctx)
    if state in DROPPABLE_STATES:
        ctx.central.drop(deployment_id)
    return True


def publish_and_follow(ctx: Context, deployment_id: str) -> None:
    deployment = wait_for(
        ctx, deployment_id, {"VALIDATED", "PUBLISHING", "PUBLISHED", "FAILED"}
    )
    state = deployment["deploymentState"]
    if state == "FAILED":
        if published_by_another(ctx, deployment_id, state):
            return
        ctx.record.save_or_warn("failure", deployment_id, ctx.staging.digest)
        raise PublishError(
            f"Deployment {deployment_id} failed validation:\n{errors_of(deployment)}\n"
            f"Running the job again drops it and uploads again. See {DEPLOYMENTS_PAGE}"
        )
    if state == "VALIDATED":
        log(f"Publishing deployment {deployment_id}")
        status, body = ctx.central.publish(deployment_id)
        if status not in (200, 202, 204):
            # A retried request may hit a deployment that the first one already moved on.
            current = ctx.central.status(deployment_id) or {}
            state = current.get("deploymentState", "unknown")
            if state not in ("PUBLISHING", "PUBLISHED"):
                if published_by_another(ctx, deployment_id, state):
                    return
                raise PublishError(
                    f"Could not publish deployment {deployment_id}: HTTP {status}: {body}"
                )
    deployment = wait_for(ctx, deployment_id, {"PUBLISHED", "FAILED"})
    if deployment["deploymentState"] == "FAILED":
        if published_by_another(ctx, deployment_id, "FAILED"):
            return
        ctx.record.save_or_warn("failure", deployment_id, ctx.staging.digest)
        raise PublishError(
            f"Publication of deployment {deployment_id} failed:\n{errors_of(deployment)}\n"
            f"See {DEPLOYMENTS_PAGE}"
        )
    ctx.record.save_or_warn("success", deployment_id, ctx.staging.digest)


def required_env(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise PublishError(f"{name} must be set")
    return value


def run(args: argparse.Namespace) -> int:
    deadline = Deadline(args.timeout_minutes)
    staging = Staging(args.staging_dir, args.version)
    ctx = Context(
        central=Central(
            args.api_url,
            required_env("JRELEASER_MAVENCENTRAL_USERNAME"),
            required_env("JRELEASER_MAVENCENTRAL_PASSWORD"),
        ),
        record=CommitStatus(
            os.environ.get("GITHUB_API_URL", "https://api.github.com"),
            required_env("GITHUB_REPOSITORY"),
            required_env("GITHUB_SHA"),
            required_env("GITHUB_TOKEN"),
            args.version,
        ),
        staging=staging,
        repository_url=args.repository_url,
        deadline=deadline,
        poll_seconds=args.poll_seconds,
    )
    coordinates = ", ".join(str(c) for c in staging.components)
    log(
        f"Staged: {coordinates}; {len(staging.payload)} jars and POMs, "
        f"payload digest sha256:{staging.digest}"
    )

    published = published_components(ctx)
    if len(published) == len(staging.components):
        log("Already published to Maven Central; comparing with the staged files")
        verify_published(ctx)
        annotate(
            "notice",
            f"Already published to Maven Central, nothing to do: {coordinates}",
        )
        return 0
    if published:
        raise PublishError(
            "Only some components are published to Maven Central, which a single bundle cannot "
            "produce. Check the deployments before going further.\n"
            f"Published: {', '.join(str(c) for c in published)}\n"
            "Not published: "
            f"{', '.join(str(c) for c in staging.components if c not in published)}\n"
            f"See {DEPLOYMENTS_PAGE}"
        )

    deployment_id = None
    recorded = ctx.record.load()
    if recorded:
        deployment_id = adopt(ctx, *recorded)
    if not deployment_id:
        ctx.record.save("pending", NO_DEPLOYMENT, staging.digest)
        deployment_id = upload(ctx, args.upload_command)

    publish_and_follow(ctx, deployment_id)
    annotate(
        "notice",
        f"Published to Maven Central (deployment {deployment_id}): {coordinates}",
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__.split("\n\n")[0].strip(),
        usage="%(prog)s [options] -- UPLOAD_COMMAND...",
        epilog="Environment: JRELEASER_MAVENCENTRAL_USERNAME, JRELEASER_MAVENCENTRAL_PASSWORD, "
        "GITHUB_TOKEN (with statuses: write), GITHUB_REPOSITORY, GITHUB_SHA, GITHUB_API_URL.",
    )
    parser.add_argument("--version", required=True, help="the version being released")
    parser.add_argument(
        "--staging-dir",
        type=Path,
        action="append",
        required=True,
        help="a staging repository JReleaser deploys from (repeatable)",
    )
    parser.add_argument("--api-url", default=DEFAULT_API_URL)
    parser.add_argument("--repository-url", default=DEFAULT_REPOSITORY_URL)
    parser.add_argument(
        "--timeout-minutes",
        type=float,
        default=60,
        help="give up waiting after this long; keep it under the job's timeout-minutes",
    )
    parser.add_argument("--poll-seconds", type=float, default=20)
    parser.add_argument(
        "upload_command",
        nargs=argparse.REMAINDER,
        help="the command uploading the bundle, after --",
    )
    args = parser.parse_args()
    if args.upload_command[:1] == ["--"]:
        args.upload_command = args.upload_command[1:]
    if not args.upload_command:
        parser.error("the upload command is missing (after --)")

    try:
        return run(args)
    except TimeoutError as e:
        annotate("error", str(e))
        return 1
    except PublishError as e:
        annotate("error", str(e))
        return 1


if __name__ == "__main__":
    sys.exit(main())
