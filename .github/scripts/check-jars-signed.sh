#!/usr/bin/env bash
# Fails unless every jar given as an argument is signed, as reported by jarsigner.
#
# jarsigner -verify exits 0 on an unsigned jar, and warns (without failing) about a certificate
# it does not trust, such as SignPath's test-signing one. So its output is checked for
# "jar verified." rather than its exit code.
set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo "usage: $0 <jar>..." >&2
  exit 2
fi

status=0
for jar in "$@"; do
  echo "::group::jarsigner -verify ${jar}"
  output="$(jarsigner -verify -verbose -certs "${jar}")"
  echo "${output}"
  echo "::endgroup::"
  if grep -q '^jar verified\.' <<< "${output}"; then
    echo "Signed: ${jar}"
  else
    echo "::error::${jar} is not signed"
    status=1
  fi
done
exit "${status}"
