#!/usr/bin/env bash
# Factory checks for NuvioTV: copy a worktree to a build dir under ~/nuvio/factory/build (keeps .gradle and build
# output out of the worktree), then assemble the full debug APK and run the JVM unit tests. Prints a short summary and the log path.
# Tests listed in .factory/known-test-failures.txt (failing on upstream dev already) do not fail the check; any other failure does.
# Usage: build.sh <worktree path, WSL or Windows> [gradle tasks...]
# local.properties comes from ~/nuvio/factory/NuvioTV.local.properties (never committed); without it the
# example file plus sdk.dir is used, which builds but cannot sign in.
set -euo pipefail
src=${1:?usage: build.sh <worktree> [tasks]}; shift
src=${src//\\//}                                                   # backslashes to slashes
[[ $src =~ ^//wsl(\.localhost|\$)/[^/]+(/.*)$ ]] && src=${BASH_REMATCH[2]}   # //wsl.localhost/Ubuntu/home/... -> /home/...
[[ $src == ?:/* ]] && src=$(wslpath -u "$src")
here=$(dirname "$(readlink -f "$0")")
tasks=("$@"); [ ${#tasks[@]} -eq 0 ] && tasks=(:app:assembleFullDebug :app:testFullDebugUnitTest)
name=$(basename "$src")
out="$HOME/nuvio/factory/build"
dst="$out/$name"
log="$out/$name.log"
mkdir -p "$dst"
# Parallel workers take turns here: one Gradle build needs ~13 GB and WSL has ~23 GB.
exec 9>"$out/.build.lock"; echo "build.sh: waiting for the build lock" >&2; flock 9
# Exclude only module-level build dirs: app/src/**/core/build is a source package.
rsync -a --delete --exclude .git --exclude /build --exclude '/*/build' --exclude .gradle --exclude .claude --exclude local.properties "$src/" "$dst/"
props="$HOME/nuvio/factory/NuvioTV.local.properties"
if [ -f "$props" ]; then cp "$props" "$dst/local.properties"
else { echo "sdk.dir=$HOME/Android/Sdk"; cat "$dst/local.example.properties"; } > "$dst/local.properties"; fi
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/sdk/jdk-21}   # the JDK the enhanced builds use; Gradle toolchains fetch 17 if needed
# Upstream signs debug with the release config; without its keystore, sign with the WSL debug key (the same key Enhanced's TV builds use).
if [ -z "${NUVIO_RELEASE_STORE_FILE:-}" ]; then
  export NUVIO_RELEASE_STORE_FILE=$HOME/.android/debug.keystore NUVIO_RELEASE_KEY_ALIAS=androiddebugkey \
    NUVIO_RELEASE_KEY_PASSWORD=android NUVIO_RELEASE_STORE_PASSWORD=android
fi
cd "$dst"
start=$(date +%s)
took() { echo "$(( $(date +%s) - start ))s"; }
if ./gradlew --console=plain --continue "${tasks[@]}" > "$log" 2>&1; then
  echo "build.sh: PASS ${tasks[*]} in $(took) (apk: $dst/app/build/outputs/apk/full/debug/, log: $log)"; exit 0
fi
# Failed: pass only if every failed task is a unit-test task and every failed test is a known upstream failure.
failed_tasks=$(grep -oE "^> Task \S+ FAILED" "$log" | awk '{print $3}' | sort -u)
new=$(grep -E "^\S+ > .+ FAILED$" "$log" | sed 's/ FAILED$//' | sort -u | grep -vxF -f <(grep -v '^#' "$here/known-test-failures.txt" 2>/dev/null) || true)
if [ -n "$failed_tasks" ] && ! grep -qv 'UnitTest$' <<<"$failed_tasks" && [ -z "$new" ]; then
  echo "build.sh: PASS ${tasks[*]} in $(took), only known upstream test failures (.factory/known-test-failures.txt) (apk: $dst/app/build/outputs/apk/full/debug/, log: $log)"; exit 0
fi
echo "build.sh: FAIL ${tasks[*]} after $(took); log: $log"
[ -n "$new" ] && { echo "new test failures:"; echo "$new"; }
grep -E "^e: |error:|What went wrong" -A2 "$log" | head -40
exit 1
