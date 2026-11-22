# shellcheck shell=bash
#
# Helpers shared by the CLI demos. Sourced, not run.
#
# The csi-codesign command defaults to the native binary on the PATH. Set CSI_CODESIGN to run
# another one, e.g. CSI_CODESIGN="java -jar /path/to/codesign-cli-0.11.0-bin.jar".

set -euo pipefail

read -r -a CODESIGN <<<"${CSI_CODESIGN:-csi-codesign}"

# Scratch directory for the sample files and the signed output. Kept after the run, so that the
# signed files can be inspected.
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/codesign-demo.XXXXXX")"

die() {
  echo "error: $*" >&2
  exit 2
}

# require_env VAR...: fails unless every variable is set and not blank.
require_env() {
  local missing=() var
  for var in "$@"; do
    [[ -n "${!var:-}" ]] || missing+=("$var")
  done
  ((${#missing[@]} == 0)) || die "set these environment variables first: ${missing[*]} (see ../README.md)"
}

# require_cli: fails unless the csi-codesign command can be found.
require_cli() {
  command -v "${CODESIGN[0]}" >/dev/null ||
    die "'${CODESIGN[0]}' not found: put csi-codesign on the PATH or set CSI_CODESIGN (see ../../USAGE.md#cli)"
}

# make_sample_jar NAME: creates an unsigned jar in WORK_DIR and prints its path.
make_sample_jar() {
  command -v jar >/dev/null || die "the 'jar' tool of a JDK is needed to build sample jars; pass your own files instead"
  local name="$1" content="$WORK_DIR/content-${1%.jar}"
  mkdir -p "$content"
  echo "Sample file signed by the Eclipse CSI codesign demos." >"$content/README.txt"
  jar --create --file "$WORK_DIR/$name" -C "$content" README.txt
  echo "$WORK_DIR/$name"
}

# run CMD...: prints the command, then runs it.
run() {
  printf '+'
  printf ' %q' "$@"
  printf '\n'
  "$@"
}

# show_signed FILE...: lists the signed files, and checks the jar signatures when jarsigner is
# available. jarsigner -verify also succeeds on unsigned jars, hence the check of its output.
show_signed() {
  local file out
  echo
  echo "Signed files:"
  for file in "$@"; do
    echo "  $file"
    if [[ "$file" == *.jar ]] && command -v jarsigner >/dev/null; then
      out="$(jarsigner -verify "$file")"
      echo "$out" | sed '/^$/d; s/^/    jarsigner: /'
      grep -qF 'jar verified.' <<<"$out" || die "$file is not signed"
    fi
  done
}
