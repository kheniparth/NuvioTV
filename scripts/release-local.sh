#!/usr/bin/env bash
# Build and publish a signed release from a clean main branch.
# Requires: git on main, clean tree, HEAD == origin/main, signing files exist.
# Usage: release-local.sh [--dry-run]

set -euo pipefail

dry_run=false
if [[ "${1:-}" == "--dry-run" ]]; then
    dry_run=true
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
secrets_dir="${NUVIO_SECRETS_DIR:-/mnt/d/secrets/nuvio}"
env_file="$secrets_dir/release.env"
keystore_path="$secrets_dir/nuvio-release.jks"

# Precondition checks
error() {
    echo "Error: $*" >&2
    exit 1
}

# Check branch
current_branch=$(git -C "$repo_root" rev-parse --abbrev-ref HEAD)
if [[ "$current_branch" != "main" ]]; then
    error "Must be on 'main' branch, currently on '$current_branch'"
fi

# Check clean tree
if ! git -C "$repo_root" diff-index --quiet HEAD --; then
    error "Working tree is not clean"
fi

if [[ -n $(git -C "$repo_root" ls-files --others --exclude-standard) ]]; then
    error "Untracked files present"
fi

# Check HEAD == origin/main
local_head=$(git -C "$repo_root" rev-parse HEAD)
origin_head=$(git -C "$repo_root" rev-parse origin/main)
if [[ "$local_head" != "$origin_head" ]]; then
    error "HEAD is not synced with origin/main"
fi

# Check signing files exist
if [[ ! -f "$keystore_path" ]]; then
    error "Keystore not found at $keystore_path; run scripts/init-release-key.sh first"
fi

if [[ ! -f "$env_file" ]]; then
    error "release.env not found at $env_file; run scripts/init-release-key.sh first"
fi

# Read release metadata
metadata_output=$("$script_dir/release-metadata.sh" HEAD)
version=$(echo "$metadata_output" | grep '^version=' | cut -d= -f2)
tag=$(echo "$metadata_output" | grep '^tag=' | cut -d= -f2)
title=$(echo "$metadata_output" | grep '^title=' | cut -d= -f2)

# Check if tag or release already exists
if git -C "$repo_root" rev-parse "$tag" >/dev/null 2>&1; then
    error "Tag '$tag' already exists"
fi

if gh release view "$tag" -R kheniparth/NuvioTV >/dev/null 2>&1; then
    error "Release '$tag' already exists"
fi

# Create throwaway detached worktree so the shared main checkout stays usable.
# .factory/build.sh rsyncs this into $HOME/nuvio/factory/build/<basename>/ and builds there.
build_worktree_name="release-$tag"
build_worktree_path="$HOME/nuvio/$build_worktree_name"
factory_build_dir="$HOME/nuvio/factory/build/$build_worktree_name"
build_log="$factory_build_dir.log"
apk_output_dir="$factory_build_dir/app/build/outputs/apk/full/release"

cleanup() {
    local status=$?
    if [[ -d "$build_worktree_path" ]]; then
        git -C "$repo_root" worktree remove --force "$build_worktree_path" 2>/dev/null || true
    fi
    exit "$status"
}
trap cleanup EXIT

echo "Worktree: $build_worktree_path"
echo "Factory build dir: $factory_build_dir"
echo "Build log: $build_log"
echo "APK directory: $apk_output_dir"

# Detached checkout of origin/main (HEAD == origin/main already enforced above).
# Must not check out branch 'main' again — it is already used by $repo_root.
git -C "$repo_root" worktree add --detach "$build_worktree_path" origin/main

# Source the release env and prepare for build
# shellcheck disable=SC1090
source "$env_file"
export NUVIO_RELEASE_STORE_FILE NUVIO_RELEASE_KEY_ALIAS NUVIO_RELEASE_KEY_PASSWORD NUVIO_RELEASE_STORE_PASSWORD

# Build the release APKs
echo "Building release APKs for $version..."
bash "$repo_root/.factory/build.sh" "$build_worktree_path" :app:assembleFullRelease

if [[ ! -d "$apk_output_dir" ]]; then
    error "APK output directory not found at $apk_output_dir (see build log: $build_log)"
fi

# Collect all APKs
readarray -t apks < <(find "$apk_output_dir" -maxdepth 1 -name "*.apk" -type f | sort)
if [[ ${#apks[@]} -eq 0 ]]; then
    error "No APK files found in $apk_output_dir"
fi

# Validate APK names for AbiSelector compatibility
# AbiSelector matches file names containing: arm64-v8a, armeabi-v7a, x86_64, x86, universal, or all
validate_apk_names() {
    local all_valid=true
    for apk in "$@"; do
        local basename=$(basename "$apk")
        if ! [[ "$basename" =~ (arm64-v8a|armeabi-v7a|x86_64|x86|universal|all) ]]; then
            echo "Warning: APK name '$basename' may not match AbiSelector patterns" >&2
            all_valid=false
        fi
    done
    if [[ "$all_valid" == false ]]; then
        return 1
    fi
}

if ! validate_apk_names "${apks[@]}"; then
    error "Some APKs have names that do not match AbiSelector expectations"
fi

echo "Found APKs:"
for apk in "${apks[@]}"; do
    echo "  $(basename "$apk")"
done

if [[ "$dry_run" == true ]]; then
    echo ""
    echo "DRY RUN: Would create release '$tag' with title '$title'"
    echo "Assets:"
    for apk in "${apks[@]}"; do
        echo "  $(basename "$apk")"
    done
    exit 0
fi

# Create annotated tag
echo "Creating tag '$tag'..."
git -C "$repo_root" tag -a "$tag" -m "$title"

# Push tag
echo "Pushing tag..."
git -C "$repo_root" push origin "$tag"

# Create release with gh
echo "Creating release..."
gh release create "$tag" \
    --title "$title" \
    --latest \
    --generate-notes \
    "${apks[@]}" \
    -R kheniparth/NuvioTV

# Verify release
echo ""
echo "Release verification:"
gh release view "$tag" --json tagName,isPrerelease,assets -R kheniparth/NuvioTV
