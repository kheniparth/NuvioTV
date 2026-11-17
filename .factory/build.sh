#!/usr/bin/env bash
# Factory checks for NuvioTV: copy a worktree to a WSL-native build dir (Gradle over /mnt/d is ~10x slower), then
# assemble the full debug APK and run the JVM unit tests. Prints a short summary and the log path.
# Usage: build.sh <worktree path, Windows or WSL> [gradle tasks...]
# local.properties comes from ~/.config/nuvio-factory/NuvioTV.local.properties (never committed); without it the
# example file plus sdk.dir is used, which builds but cannot sign in.
set -euo pipefail
src=${1:?usage: build.sh <worktree> [tasks]}; shift
[[ $src == *:* || $src == *\\* ]] && src=$(wslpath -u "$src")
tasks=("$@"); [ ${#tasks[@]} -eq 0 ] && tasks=(:app:assembleFullDebug :app:testFullDebugUnitTest)
name=$(basename "$src")
dst="$HOME/.cache/nuvio-factory/$name"
log="$HOME/.cache/nuvio-factory/$name.log"
mkdir -p "$dst"
# Exclude only module-level build dirs: app/src/**/core/build is a source package.
rsync -a --delete --exclude .git --exclude /build --exclude '/*/build' --exclude .gradle --exclude local.properties "$src/" "$dst/"
props="$HOME/.config/nuvio-factory/NuvioTV.local.properties"
if [ -f "$props" ]; then cp "$props" "$dst/local.properties"
else { echo "sdk.dir=$HOME/Android/Sdk"; cat "$dst/local.example.properties"; } > "$dst/local.properties"; fi
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/sdk/jdk-21}   # the JDK the enhanced builds use; Gradle toolchains fetch 17 if needed
cd "$dst"
start=$(date +%s)
if ./gradlew --console=plain "${tasks[@]}" > "$log" 2>&1; then
  echo "build.sh: PASS ${tasks[*]} in $(( $(date +%s) - start ))s (apk: $dst/app/build/outputs/apk/full/debug/, log: $log)"
else
  echo "build.sh: FAIL ${tasks[*]} after $(( $(date +%s) - start ))s; log: $log"
  grep -E "^e: |FAILED|error:|What went wrong" -A2 "$log" | head -40
  exit 1
fi
