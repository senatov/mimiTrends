#!/bin/zsh

set -euo pipefail

readonly dmg_file="${1:?usage: notarize-macos-dmg.zsh DMG_FILE NOTARYTOOL_AUTH_ARGS...}"
shift
[[ -f "$dmg_file" ]] || { print -u2 "DMG does not exist: $dmg_file"; exit 1; }
(( $# > 0 )) || { print -u2 "notarytool authentication arguments are required"; exit 1; }

result_file="$(mktemp "${TMPDIR:-/tmp}/mimitrends-notary-result.XXXXXX")"
cleanup() { rm -f "$result_file"; }
trap cleanup EXIT INT TERM

xcrun notarytool submit "$dmg_file" --wait --output-format json "$@" | tee "$result_file"
status="$(plutil -extract status raw -o - "$result_file")"
submission_id="$(plutil -extract id raw -o - "$result_file")"

if [[ "$status" != "Accepted" ]]; then
  print -u2 "Apple notarization rejected ${dmg_file:t}: status=$status submission=$submission_id"
  xcrun notarytool log "$submission_id" "$@" || true
  exit 1
fi

print "Apple notarization accepted ${dmg_file:t}: submission=$submission_id"
