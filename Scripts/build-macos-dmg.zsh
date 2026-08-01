#!/bin/zsh

set -euo pipefail

readonly SCRIPT_DIR="${0:A:h}"
readonly SCRIPT_NAME="${0:t}"
readonly PROJECT_DIR="${SCRIPT_DIR:h}"
readonly GRADLEW="${PROJECT_DIR}/gradlew"
readonly OUTPUT_DIR="${PROJECT_DIR}/app/build/distributions/native/macos"

notarize=false
notary_profile="${APPLE_NOTARY_PROFILE:-MiMiNotary}"
signing_identity="${MAC_SIGNING_KEY_USER_NAME:-}"

usage() {
  print "Usage: $SCRIPT_NAME [--notarize] [--identity NAME] [--profile NAME]"
  print
  print "Builds a self-contained Developer ID signed MiMiTrends DMG."
  print "With --notarize, also submits it to Apple, staples, and validates it."
  print
  print "Options:"
  print "  --notarize       Run the complete Apple notarization workflow"
  print "  --identity NAME  Developer ID Application identity"
  print "  --profile NAME   notarytool keychain profile (default: MiMiNotary)"
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
      shift
      ;;
    --identity)
      (( $# >= 2 )) || fail "--identity requires a value"
      signing_identity="$2"
      shift 2
      ;;
    --profile)
      (( $# >= 2 )) || fail "--profile requires a value"
      notary_profile="$2"
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

[[ "$(uname -s)" == "Darwin" ]] || fail "DMG packages can only be built on macOS"
[[ -x "$GRADLEW" ]] || fail "Gradle wrapper is missing or not executable: $GRADLEW"
command -v security >/dev/null || fail "macOS security tool is unavailable"
command -v xcrun >/dev/null || fail "Xcode Command Line Tools are required"

if [[ -z "$signing_identity" ]]; then
  signing_identity="$(
    security find-identity -v -p codesigning 2>/dev/null \
      | sed -nE 's/.*"Developer ID Application: ([^"]+)".*/\1/p' \
      | head -n 1
  )"
fi

[[ -n "$signing_identity" ]] || fail \
  "no Developer ID Application certificate found; inspect Keychain with: security find-identity -v -p codesigning"

if $notarize; then
  [[ -n "$notary_profile" ]] || fail "a notarytool keychain profile is required"
  if ! xcrun notarytool history --keychain-profile "$notary_profile" >/dev/null 2>&1; then
    fail "notarytool profile '$notary_profile' is unavailable; see Doc/NativePackaging.md"
  fi
fi

print "MiMiTrends macOS package"
print "  Project:  $PROJECT_DIR"
print "  Identity: Developer ID Application: $signing_identity"
print "  Mode:     $($notarize && print 'signed + notarized' || print 'signed')"

cd "$PROJECT_DIR"

if $notarize; then
  APPLE_NOTARY_PROFILE="$notary_profile" \
  MAC_SIGNING_KEY_USER_NAME="$signing_identity" \
    "$GRADLEW" :app:packageNotarizedMacDmg
else
  MAC_SIGNING_KEY_USER_NAME="$signing_identity" \
    "$GRADLEW" :app:packageMacDmg
fi

dmg_files=("$OUTPUT_DIR"/*.dmg(N.om))
(( ${#dmg_files} > 0 )) || fail "Gradle completed but no DMG was found in $OUTPUT_DIR"
readonly dmg_file="${dmg_files[1]}"

print
print "Created: $dmg_file"
ls -lh "$dmg_file"
shasum -a 256 "$dmg_file"
