#!/bin/zsh
set -euo pipefail

readonly dmg_file="${1:?usage: verify-macos-dmg.zsh DMG_FILE}"
[[ -f "$dmg_file" ]] || { print -u2 "DMG does not exist: $dmg_file"; exit 1; }
mount_dir=""
work_dir=""
cleanup() {
  [[ -z "$mount_dir" ]] || hdiutil detach "$mount_dir" -quiet || true
  [[ -z "$work_dir" ]] || rm -rf "$work_dir"
}
trap cleanup EXIT INT TERM

codesign --verify --strict --verbose=2 "$dmg_file"
mount_dir="$(hdiutil attach -readonly -nobrowse "$dmg_file" | awk '/\/Volumes\// {sub(/^.*\/Volumes\//, "/Volumes/"); print; exit}')"
[[ -n "$mount_dir" && -d "$mount_dir" ]] || { print -u2 "Unable to mount $dmg_file"; exit 1; }
app_paths=("$mount_dir"/*.app(N))
(( ${#app_paths} == 1 )) || { print -u2 "Expected one app in $dmg_file"; exit 1; }
readonly app="${app_paths[1]}"

codesign --verify --deep --strict --verbose=2 "$app"
app_signature_info="$(codesign -dvv "$app" 2>&1)"
grep -q '^Authority=Developer ID Application:' <<< "$app_signature_info" || {
  print -u2 "Missing Developer ID signature: $app"; exit 1
}
grep -q '^Timestamp=' <<< "$app_signature_info" || {
  print -u2 "Missing secure timestamp: $app"; exit 1
}
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/mimitrends-dmg-verify.XXXXXX")"
typeset -i verified_count=0
for jar_file in "$app"/Contents/app/*.jar(N); do
  jar_entries="$(unzip -Z1 "$jar_file")"
  grep -Eq '\.(dylib|jnilib)$' <<< "$jar_entries" || continue
  jar_dir="$work_dir/${jar_file:t:r}"
  mkdir -p "$jar_dir"
  unzip -qq "$jar_file" -d "$jar_dir"
  while IFS= read -r -d '' native_file; do
    file -b "$native_file" | grep -q 'Mach-O' || continue
    codesign --verify --strict --verbose=2 "$native_file"
    signature_info="$(codesign -dvv "$native_file" 2>&1)"
    grep -q '^Authority=Developer ID Application:' <<< "$signature_info" || {
      print -u2 "Missing Developer ID signature: $native_file"; exit 1
    }
    grep -q '^Timestamp=' <<< "$signature_info" || {
      print -u2 "Missing secure timestamp: $native_file"; exit 1
    }
    verified_count+=1
  done < <(find "$jar_dir" -type f \( -name '*.dylib' -o -name '*.jnilib' \) -print0)
done

(( verified_count > 0 )) || { print -u2 "No embedded Mach-O libraries were verified"; exit 1; }
print "Verified app signature and $verified_count embedded Mach-O libraries in ${dmg_file:t}."
