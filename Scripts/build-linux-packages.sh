#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_NAME="$(basename -- "$0")"
readonly PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly GRADLEW="$PROJECT_DIR/gradlew"
readonly OUTPUT_DIR="$PROJECT_DIR/app/build/distributions/native/linux"

build_portable=true
build_deb=true

usage() {
  printf 'Usage: %s [--portable-only | --deb-only]\n\n' "$SCRIPT_NAME"
  printf '%s\n' 'Builds self-contained MiMiTrends packages for Linux.'
  printf '%s\n' 'By default it creates both a portable tar.gz and a Debian/Ubuntu .deb package.'
  printf '\nOptions:\n'
  printf '%s\n' '  --portable-only  Build only the portable tar.gz archive'
  printf '%s\n' '  --deb-only       Build only the Debian/Ubuntu package'
  printf '%s\n' '  -h, --help       Show this help'
}

fail() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

while (( $# > 0 )); do
  case "$1" in
    --portable-only)
      build_portable=true
      build_deb=false
      ;;
    --deb-only)
      build_portable=false
      build_deb=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown option: $1"
      ;;
  esac
  shift
done

[[ "$(uname -s)" == "Linux" ]] || fail "Linux packages can only be built on Linux"
[[ -x "$GRADLEW" ]] || fail "Gradle wrapper is missing or not executable: $GRADLEW"
command -v sha256sum >/dev/null || fail "sha256sum is required"

if $build_deb; then
  command -v fakeroot >/dev/null || fail "fakeroot is required to build the Debian package"
  command -v dpkg-deb >/dev/null || fail "dpkg-deb is required to build the Debian package"
fi

tasks=()
$build_portable && tasks+=(":app:packageLinuxPortable")
$build_deb && tasks+=(":app:packageLinuxDeb")

printf 'MiMiTrends Linux package\n'
printf '  Project: %s\n' "$PROJECT_DIR"
printf '  Formats: %s\n' "$($build_portable && printf 'portable tar.gz'; $build_portable && $build_deb && printf ' + '; $build_deb && printf 'Debian .deb')"

cd "$PROJECT_DIR"
"$GRADLEW" "${tasks[@]}"

artifacts=()
if $build_portable; then
  shopt -s nullglob
  artifacts+=("$OUTPUT_DIR"/*.tar.gz)
  shopt -u nullglob
fi
if $build_deb; then
  shopt -s nullglob
  artifacts+=("$OUTPUT_DIR"/*.deb)
  shopt -u nullglob
fi

(( ${#artifacts[@]} > 0 )) || fail "Gradle completed but no package was found in $OUTPUT_DIR"

printf '\nCreated packages:\n'
for artifact in "${artifacts[@]}"; do
  printf '  %s\n' "$artifact"
  ls -lh "$artifact"
  sha256sum "$artifact"
done
