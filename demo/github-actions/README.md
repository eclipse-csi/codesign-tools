# GitHub Actions demos

Example workflows that sign files in GitHub Actions. They don't run from here: copy one to
`.github/workflows/` of your repository.

| Workflow | Tool | Submission | Use it when |
| --- | --- | --- | --- |
| [`sign-with-action.yml`](sign-with-action.yml) | [`eclipse-csi/codesign-action`](https://github.com/eclipse-csi/codesign-action) | GitHub connector (trusted build system) | You sign files outside of a Maven build. **Recommended for Eclipse Foundation projects.** |
| [`maven-plugin-trusted-build.yml`](maven-plugin-trusted-build.yml) | `codesign-maven-plugin` | GitHub connector (trusted build system) | You build with Maven and want the jars signed before `install`/`deploy`. |
| [`cli-direct-mode.yml`](cli-direct-mode.yml) | `csi-codesign` | Direct upload to the REST API | Your signing policy has no trusted build system verification (not Eclipse Foundation). |

## Setup

In your repository settings, define:

- the **variables** `SIGNPATH_ORGANIZATION_ID`, `SIGNPATH_PROJECT_ID` and
  `SIGNPATH_SIGNING_POLICY`;
- the **secret** `SIGNPATH_API_TOKEN`, the API token of the SignPath CI user.

The workflows use the artifact configurations of [`../signpath/`](../signpath/).

## Trusted build system

Signing policies with **trusted build system verification**, as used by Eclipse Foundation
projects, reject files uploaded directly to the REST API. SignPath has to fetch them from GitHub
itself, to check which repository, workflow and branch built them. The first two workflows do
that: the files are stored as an artifact of the running job (kept for one day), which is
submitted through the SignPath GitHub connector. For this:

- the job needs the `actions: read` permission, so that SignPath can download the artifact with
  `GITHUB_TOKEN`;
- the signing policy's origin verification must accept the repository, workflow and branch or
  tag. Otherwise the log lists each violation, with SignPath's hint on how to fix it;
- file names must be unique within a workflow run when files are signed one at a time, because
  a single-file artifact is named after the file, even across the jobs of a matrix.

`codesign-action` handles the rest. The Maven plugin also needs the job's Actions runtime
credentials, which GitHub only gives to JavaScript actions: the
[`expose-actions-runtime`](../../.github/actions/expose-actions-runtime/action.yml) action of this
repository hands them to the Maven step.

See [GitHub Actions: Trusted Build System](../../USAGE.md#github-actions-trusted-build-system)
in USAGE.md.

## Pinning

The workflows pin every action to a full commit SHA, with the version in a comment, so that
Dependabot can update them. `codesign-action` has no release yet, so its pin is a commit of its
`main` branch: switch to a `v1` release once one is published.
