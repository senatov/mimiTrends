#!/bin/zsh

set -euo pipefail

readonly SCRIPT_DIR="${0:A:h}"
readonly PROJECT_DIR="${SCRIPT_DIR:h}"
readonly VERSION_FILE="${1:-${PROJECT_DIR}/gradle.properties}"

[[ -f "$VERSION_FILE" ]] || {
  print -u2 "Version file does not exist: $VERSION_FILE"
  exit 1
}

readonly version_lines="$(grep -c '^appVersion=' "$VERSION_FILE" || true)"
[[ "$version_lines" == "1" ]] || {
  print -u2 "Expected exactly one appVersion entry in $VERSION_FILE"
  exit 1
}

readonly current="$(sed -n 's/^appVersion=//p' "$VERSION_FILE")"
if [[ ! "$current" =~ '^([0-9]+)\.([0-9]+)\.([0-9]+)$' ]]; then
  print -u2 "appVersion must use major.minor.patch: $current"
  exit 1
fi

major="${match[1]}"
minor="${match[2]}"
patch="${match[3]}"
(( patch += 1 ))
if (( patch == 10 )); then
  patch=0
  (( minor += 1 ))
  if (( minor == 10 )); then
    minor=0
    (( major += 1 ))
  fi
fi
readonly next_version="${major}.${minor}.${patch}"
readonly temporary="$(mktemp "${VERSION_FILE:h}/.gradle-properties.XXXXXX")"
trap 'rm -f "$temporary"' EXIT
awk -v version="$next_version" '
  /^appVersion=/ { print "appVersion=" version; next }
  { print }
' "$VERSION_FILE" > "$temporary"
mv "$temporary" "$VERSION_FILE"
trap - EXIT

print "$next_version"
