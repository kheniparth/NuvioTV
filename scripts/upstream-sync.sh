#!/usr/bin/env bash
# Merge upstream/dev onto sync/upstream-YYYYMMDD (local date), print the new
# upstream commits one per line, then run the checks. Start from a clean dev.
# Does not push.
#   bash scripts/upstream-sync.sh
# shortcut: NUVIO_SYNC_CHECKS replaces Gradle, so the unit test can run inside build.sh; leave it unset for a real sync.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

[[ "$(git rev-parse --abbrev-ref HEAD)" == "dev" ]] || { echo "checkout dev first" >&2; exit 1; }
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "working tree is dirty" >&2
  exit 1
fi
git remote get-url upstream >/dev/null 2>&1 || { echo "no upstream remote" >&2; exit 1; }

branch="sync/upstream-$(date +%Y%m%d)"
if git show-ref --verify --quiet "refs/heads/$branch"; then
  echo "branch $branch already exists" >&2
  exit 1
fi

old="$(git rev-parse HEAD)"
git fetch upstream
git switch -c "$branch"
git merge --no-edit upstream/dev

echo "upstream commits:"
lines="$(git --no-pager log --format='%h %s' "$old..upstream/dev")"
if [[ -z "$lines" ]]; then
  echo "(none)"
else
  printf '%s\n' "$lines"
fi

if [[ -n "${NUVIO_SYNC_CHECKS:-}" ]]; then
  bash -c "$NUVIO_SYNC_CHECKS"
else
  bash .factory/build.sh "$root"
fi
