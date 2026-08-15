#!/bin/zsh

set -euo pipefail

readonly SCRIPT_DIR="${0:A:h}"
readonly SCRIPT_NAME="${0:t}"
readonly PROJECT_DIR="${SCRIPT_DIR:h}"
readonly BUILD_SCRIPT="${SCRIPT_DIR}/build-macos-dmg.zsh"
readonly VERIFY_SCRIPT="${SCRIPT_DIR}/verify-macos-dmg.zsh"
readonly NOTES_SCRIPT="${SCRIPT_DIR}/generate-release-notes.zsh"
readonly OUTPUT_DIR="${PROJECT_DIR}/app/build/distributions/native/macos"
readonly REPOSITORY="senatov/mimiTrends"

notarize=false
draft=false
prerelease=false
build_args=()

usage() {
  print "Usage: $SCRIPT_NAME [--notarize] [--draft] [--prerelease] [--identity NAME] [--profile NAME]"
  print
  print "Builds a new MiMiTrends DMG and publishes it as a GitHub release."
  print "The build increments appVersion; the resulting release tag is v<version>."
  print
  print "Options:"
  print "  --notarize       Submit to Apple, staple, and validate before publishing"
  print "  --draft          Create the GitHub release as a draft"
  print "  --prerelease     Mark the GitHub release as a prerelease"
  print "  --identity NAME  Developer ID Application identity"
  print "  --profile NAME   notarytool keychain profile"
  print "  -h, --help       Show this help"
}

fail() {
  print -u2 "Error: $*"
  exit 1
}

while (( $# > 0 )); do
  case "$1" in
    --notarize)
      notarize=true
      build_args+=("$1")
      shift
      ;;
    --draft)
      draft=true
      shift
      ;;
    --prerelease)
      prerelease=true
      shift
      ;;
    --identity|--profile)
      (( $# >= 2 )) || fail "$1 requires a value"
      build_args+=("$1" "$2")
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown option: $1"
      ;;
  esac
done

[[ -x "$BUILD_SCRIPT" ]] || fail "build script is missing or not executable: $BUILD_SCRIPT"
[[ -x "$NOTES_SCRIPT" ]] || fail "release-notes script is missing or not executable: $NOTES_SCRIPT"
command -v gh >/dev/null || fail "GitHub CLI is required; install it with: brew install gh"
gh auth status >/dev/null 2>&1 || fail "GitHub CLI is not authenticated; run: gh auth login"

print "MiMiTrends GitHub release"
print "  Repository: $REPOSITORY"
print "  Mode:       $($notarize && print 'signed + notarized' || print 'signed')"

"$BUILD_SCRIPT" "${build_args[@]}"

readonly app_version="$(sed -n 's/^appVersion=//p' "$PROJECT_DIR/gradle.properties")"
[[ "$app_version" == <->.<->.<-> ]] || fail "unexpected appVersion after build: $app_version"
readonly tag="v${app_version}"

dmg_files=("$OUTPUT_DIR"/*.dmg(N.om))
(( ${#dmg_files} > 0 )) || fail "no DMG was found in $OUTPUT_DIR"
readonly dmg_file="${dmg_files[1]}"

if gh release view "$tag" --repo "$REPOSITORY" >/dev/null 2>&1; then
  fail "GitHub release $tag already exists; refusing to overwrite it"
fi

if [[ -x "$VERIFY_SCRIPT" ]]; then
  "$VERIFY_SCRIPT" "$dmg_file"
fi

readonly notes_file="$(mktemp "${TMPDIR:-/tmp}/mimitrends-release-notes.XXXXXX")"
trap 'rm -f "$notes_file"' EXIT
"$NOTES_SCRIPT" "$PROJECT_DIR" "$REPOSITORY" "$notes_file"
print
print "Release notes:"
print -- "--------------"
cat "$notes_file"
print -- "--------------"

release_args=(
  "$tag"
  "$dmg_file"
  --repo "$REPOSITORY"
  --title "MiMiTrends $app_version"
  --notes-file "$notes_file"
  --target "$(git -C "$PROJECT_DIR" rev-parse HEAD)"
)
$draft && release_args+=(--draft)
$prerelease && release_args+=(--prerelease)

gh release create "${release_args[@]}"
rm -f "$notes_file"
trap - EXIT

print
print "Published: https://github.com/$REPOSITORY/releases/tag/$tag"
print "Remember to commit the appVersion change when you are ready."
