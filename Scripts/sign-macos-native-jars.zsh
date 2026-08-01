#!/bin/zsh
set -euo pipefail

readonly input_dir="${1:?usage: sign-macos-native-jars.zsh INPUT_DIR SIGNING_IDENTITY}"
readonly requested_identity="${2:?usage: sign-macos-native-jars.zsh INPUT_DIR SIGNING_IDENTITY}"
[[ -d "$input_dir" ]] || { print -u2 "Input directory does not exist: $input_dir"; exit 1; }

if [[ "$requested_identity" == "Developer ID Application:"* ]]; then
  signing_identity="$requested_identity"
else
  signing_identity="Developer ID Application: $requested_identity"
fi
available_identities="$(security find-identity -v -p codesigning)"
grep -Fq "\"$signing_identity\"" <<< "$available_identities" || {
  print -u2 "Developer ID identity not found: $signing_identity"; exit 1
}

typeset -i signed_count=0
typeset -i jar_count=0
for jar_file in "$input_dir"/*.jar(N); do
  jar_entries="$(unzip -Z1 "$jar_file")"
  grep -Eq '\.(dylib|jnilib)$' <<< "$jar_entries" || continue
  work_dir="$(mktemp -d "${TMPDIR:-/tmp}/mimitrends-native-jar.XXXXXX")"
  unzip -qq "$jar_file" -d "$work_dir"
  jar_signed=false
  while IFS= read -r -d '' native_file; do
    file -b "$native_file" | grep -q 'Mach-O' || continue
    codesign --force --sign "$signing_identity" --timestamp --options runtime "$native_file"
    codesign --verify --strict --verbose=2 "$native_file"
    signed_count+=1
    jar_signed=true
  done < <(find "$work_dir" -type f \( -name '*.dylib' -o -name '*.jnilib' \) -print0)
  if $jar_signed; then
    rm -f "$jar_file"
    (cd "$work_dir" && zip -q -r -X "$jar_file" .)
    jar_count+=1
    print "Signed native libraries in ${jar_file:t}"
  fi
  rm -rf "$work_dir"
done

(( signed_count > 0 )) || { print -u2 "No Mach-O libraries were found in $input_dir"; exit 1; }
print "Signed $signed_count Mach-O libraries across $jar_count JAR files."
