#!/usr/bin/env bash
#
# Doc-honesty guard: every relative link in a tracked Markdown file must resolve.
#
# Markdown rots silently — a renamed file or moved method leaves a dead link, and once a
# reader hits one dead link they stop trusting every doc. This check fails the build on any
# broken intra-repo link (relative paths, including links into source code), so the docs
# stay load-bearing. External (http/https/mailto) and pure-anchor (#section) links are not
# checked here. Fenced code blocks are ignored to avoid false positives from shell snippets.
#
# Usage:  scripts/check-doc-links.sh
# Exit:   0 = all links resolve; 1 = at least one broken link (details on stderr).

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

mapfile -t md_files < <(git ls-files '*.md')

errors="$(mktemp)"
trap 'rm -f "$errors"' EXIT

for md in "${md_files[@]}"; do
  dir="$(dirname "$md")"

  # Strip fenced code blocks, then extract inline link/image targets.
  while IFS= read -r target; do
    target="${target%% *}"      # drop optional "title"
    base="${target%%#*}"         # drop anchor
    case "$target" in
      http://*|https://*|mailto:*|"#"*|"") continue ;;
    esac
    [ -z "$base" ] && continue
    if [ ! -e "$dir/$base" ]; then
      echo "BROKEN  $md  ->  $target" >>"$errors"
    fi
  done < <(
    awk '/^[[:space:]]*```/ { fence = !fence; next } !fence { print }' "$md" \
      | grep -oE '\]\([^)]+\)' \
      | sed -E 's/^\]\(//; s/\)$//'
  )
done

echo "Checked ${#md_files[@]} Markdown files."
if [ -s "$errors" ]; then
  echo "" >&2
  cat "$errors" >&2
  echo "" >&2
  echo "FAILED: $(grep -c '^BROKEN' "$errors") broken link(s)." >&2
  exit 1
fi
echo "OK: all relative doc links resolve."
