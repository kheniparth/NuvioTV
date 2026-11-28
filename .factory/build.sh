#!/usr/bin/env bash
# Factory checks for NuvioTV: copy a worktree to a build dir under ~/nuvio/factory/build (keeps .gradle and build
# output out of the worktree), then assemble the full debug APK and run the JVM unit tests. Prints a short summary and the log path.
# On the way out, stops the Gradle and Kotlin daemons so they do not keep ~12 GB resident (no room left for the emulator).
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
# Gradle heap is 6 GB (gradle.properties) and the Kotlin daemon is another 6 GB that idles for 2 hours.
# Stopping on EXIT keeps that speed for this build. In-process Kotlin would share the 6 GB Gradle heap.
stop_daemons() {
  local status=$?
  ./gradlew --stop >>"$log" 2>&1 || true
  # -f matches the daemon main class. pkill's own argv does not contain that class name.
  pkill -u "$(id -u)" -f 'org.jetbrains.kotlin.daemon.KotlinCompileDaemon' >/dev/null 2>&1 || true
  exit "$status"
}
trap stop_daemons EXIT
start=$(date +%s)
took() { echo "$(( $(date +%s) - start ))s"; }
# Release tasks land in apk/full/release; everything else this script builds is the debug APK.
apk_out() {
  local paths=""
  [[ ${tasks[*]} == *Debug* ]] && paths="$dst/app/build/outputs/apk/full/debug/"
  [[ ${tasks[*]} == *Release* ]] && paths="${paths:+$paths }$dst/app/build/outputs/apk/full/release/"
  echo "${paths:-$dst/app/build/outputs/apk/full/debug/}"
}
if ./gradlew --console=plain --continue "${tasks[@]}" > "$log" 2>&1; then
  echo "build.sh: PASS ${tasks[*]} in $(took) (apk: $(apk_out), log: $log)"; exit 0
fi
# Failed: pass only if every failed task is a unit-test task and every failed test is a known upstream failure.
failed_tasks=$(grep -oE "^> Task \S+ FAILED" "$log" | awk '{print $3}' | sort -u)
new=$(grep -E "^\S+ > .+ FAILED$" "$log" | sed 's/ FAILED$//' | sort -u | grep -vxF -f <(grep -v '^#' "$here/known-test-failures.txt" 2>/dev/null) || true)
if [ -n "$failed_tasks" ] && ! grep -qv 'UnitTest$' <<<"$failed_tasks" && [ -z "$new" ]; then
  echo "build.sh: PASS ${tasks[*]} in $(took), only known upstream test failures (.factory/known-test-failures.txt) (apk: $(apk_out), log: $log)"; exit 0
fi
echo "build.sh: FAIL ${tasks[*]} after $(took); log: $log"
[ -n "$new" ] && { echo "new test failures:"; echo "$new"; }
grep -E "^e: |error:|What went wrong" -A2 "$log" | head -40
exit 1
