# CLI demos

Shell scripts that sign files with `csi-codesign`, the standalone CLI. They run anywhere bash
does, so the same commands fit any CI system (Jenkins, GitLab CI, …).

## Setup

1. Install `csi-codesign` ([installation](../../USAGE.md#cli)), or set `CSI_CODESIGN` to the
   command to run instead, e.g. `CSI_CODESIGN="java -jar codesign-cli-0.11.0-bin.jar"`.
2. Create the artifact configurations of [`../signpath/`](../signpath/) in your SignPath project.
3. Export the environment variables listed in [`../README.md`](../README.md#prerequisites).

Each script signs the files given as arguments, or sample jars it builds with the JDK `jar`
tool. Its output goes to a fresh `codesign-demo.*` directory under `$TMPDIR`, kept after the run
for inspection, and the signatures of the signed jars are checked with `jarsigner -verify`.
Set `SIGNPATH_ARTIFACT_CONFIGURATION` to use another artifact configuration slug than the
script's default.

## Scripts

| Script | Shows | Artifact configuration |
| --- | --- | --- |
| [`01-single-file.sh`](01-single-file.sh) | One file, signed copy written with `--output` | `jar` |
| [`02-several-files.sh`](02-several-files.sh) | Several files in one request, written with `--output-dir` | `jars-in-zip` |
| [`03-in-place.sh`](03-in-place.sh) | In-place replacement with `--force-overwrite` | `jar` |
| [`04-description-and-params.sh`](04-description-and-params.sh) | `--description` and `--param key=value` | `jar-with-parameters` |
| [`05-config-file-token.sh`](05-config-file-token.sh) | API token from `--config-file` instead of the environment | `jar` |
| [`06-jvm-fat-jar.sh`](06-jvm-fat-jar.sh) | The `-bin` fat jar from Maven Central, verified, run with `java -jar` | `jar` |

```bash
./01-single-file.sh                      # sign a sample jar
./01-single-file.sh dist/installer.exe   # sign your own file (with a matching configuration)
SIGNPATH_ARTIFACT_CONFIGURATION=binaries-in-zip ./02-several-files.sh dist/*.exe dist/*.jar
```

## What to remember

- **One invocation is one signing request.** One file is sent as is; several files are packed
  at the root of a zip file, so they need a `<zip-file>` artifact configuration and distinct
  names. Use one invocation per file to get one request per file instead.
- **Choose where the output goes**: `--output` (one file), `--output-dir` (any number), or
  `--force-overwrite` to replace the inputs. The CLI refuses to overwrite without it.
- **Nothing is written on failure**: the signed files are only written once the request
  completes and the signed artifact holds every file.
- `--wait-for-completion-timeout` (default 600 s) bounds the wait, e.g. for a manual approval.
  A request that times out stays open in SignPath.
- Exit codes: `0` signed, `1` signing or API failure, `2` bad arguments.

See the [CLI man page](https://eclipse-csi.github.io/codesign-tools/latest/cli-manpage/) for
every option.
