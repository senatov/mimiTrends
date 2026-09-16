#!/bin/zsh

set -euo pipefail

readonly PROJECT_DIR="${1:?usage: generate-release-notes.zsh PROJECT_DIR REPOSITORY OUTPUT_FILE}"
readonly REPOSITORY="${2:?usage: generate-release-notes.zsh PROJECT_DIR REPOSITORY OUTPUT_FILE}"
readonly OUTPUT_FILE="${3:?usage: generate-release-notes.zsh PROJECT_DIR REPOSITORY OUTPUT_FILE}"
readonly app_version="$(sed -n 's/^appVersion=//p' "$PROJECT_DIR/gradle.properties")"

previous_tag="${PREVIOUS_RELEASE_TAG:-$(
  gh release list --repo "$REPOSITORY" --limit 1 --json tagName --jq '.[0].tagName // empty'
)}"
if [[ -n "$previous_tag" ]] && ! git -C "$PROJECT_DIR" rev-parse --verify --quiet "$previous_tag^{commit}" >/dev/null; then
  git -C "$PROJECT_DIR" fetch --quiet origin "refs/tags/${previous_tag}:refs/tags/${previous_tag}"
fi

range="HEAD"
[[ -z "$previous_tag" ]] || range="$previous_tag..HEAD"
typeset -a new_items fix_items change_items documentation_items other_items

while IFS=$'\t' read -r hash subject; do
  [[ -n "$subject" ]] || continue
  prefix="${subject%%:*}"
  detail="${subject#*:}"
  if [[ "$detail" == "$subject" ]]; then
    prefix="OTHER"
    detail="$subject"
  fi
  detail="${detail#${detail%%[![:space:]]*}}"
  item="- ${detail} (\`$hash\`)"
  case "${prefix:u}" in
    NEW|FEAT|FEATURE) new_items+=("$item") ;;
    FIX|BUGFIX) fix_items+=("$item") ;;
    CHANGE|CHANGED|REFACTOR|PERF) change_items+=("$item") ;;
    DOC|DOCS) documentation_items+=("$item") ;;
    *) other_items+=("$item") ;;
  esac
done < <(git -C "$PROJECT_DIR" log "$range" --no-merges --format=$'%h\t%s')

if (( ${#new_items} + ${#fix_items} + ${#change_items} + ${#documentation_items} + ${#other_items} == 0 )); then
  # A release build bumps appVersion before the source tree is committed. In
  # that valid case the previous release tag can equal HEAD, so there are no
  # commits to enumerate even though the DMG contains a new application version.
  other_items+=("- Version bump to ${app_version}")
fi

{
  print "## What's changed"
  if (( ${#new_items} )); then
    print "\n### New"
    print -l -- "${new_items[@]}"
  fi
  if (( ${#fix_items} )); then
    print "\n### Fixes"
    print -l -- "${fix_items[@]}"
  fi
  if (( ${#change_items} )); then
    print "\n### Changes"
    print -l -- "${change_items[@]}"
  fi
  if (( ${#documentation_items} )); then
    print "\n### Documentation"
    print -l -- "${documentation_items[@]}"
  fi
  if (( ${#other_items} )); then
    print "\n### Other changes"
    print -l -- "${other_items[@]}"
  fi
  if [[ -n "$previous_tag" ]]; then
    print "\n**Full changelog:** https://github.com/$REPOSITORY/compare/$previous_tag...$(git -C "$PROJECT_DIR" rev-parse HEAD)"
  fi
} > "$OUTPUT_FILE"

print "Generated release notes from $range"
