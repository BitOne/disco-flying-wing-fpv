#!/usr/bin/env bash
# Sync the Unified GroundSdk workspace into sdk/ (runs inside the sdk-build container).
#
# Reproducibility: the manifest repo is pinned to MANIFEST_REV. After the first
# sync, the exact revision of every project is written to sdk.lock.xml (committed).
# When sdk.lock.xml exists, later syncs use it instead of the manifest's default.
#
# Local fixes: patches/<project path>/*.patch (e.g. patches/packages/groundsdk-android/)
# are applied in name order after the sync. Patched projects are reset before
# syncing so repo never sees local changes; applying is therefore idempotent.
set -euo pipefail

MANIFEST_URL=${MANIFEST_URL:-https://github.com/UnifiedGroundSdk/groundsdk-manifest}
MANIFEST_REV=${MANIFEST_REV:-9b821bfd593884c7f6408ebb61fab700f1b7b0aa}
ROOT=/work
SDK_DIR=$ROOT/sdk
LOCK=$ROOT/sdk.lock.xml
JOBS=${JOBS:-8}

mkdir -p "$SDK_DIR"
cd "$SDK_DIR"

if [ ! -d .repo ]; then
    repo init --no-repo-verify -u "$MANIFEST_URL" -b "$MANIFEST_REV" -m default.xml
fi

PATCHES=$ROOT/patches
patched_projects() {
    [ -d "$PATCHES" ] || return 0
    find "$PATCHES" -name '*.patch' -printf '%h\n' | sort -u | sed "s|^$PATCHES/||"
}

# Reset patched projects (drop previously applied patches) before syncing.
for project in $(patched_projects); do
    if [ -d "$project/.git" ] || [ -f "$project/.git" ]; then
        git -C "$project" checkout -q -- .
    fi
done

if [ -f "$LOCK" ]; then
    echo "== repo sync from $LOCK"
    cp "$LOCK" .repo/manifests/disco-fpv-lock.xml
    repo sync -c --no-tags -j"$JOBS" -m disco-fpv-lock.xml
else
    echo "== repo sync from manifest $MANIFEST_REV (no lock yet)"
    repo sync -c --no-tags -j"$JOBS"
    repo --no-pager manifest -r -o "$LOCK"
    echo "== wrote $LOCK"
fi

for project in $(patched_projects); do
    for patch in $(ls "$PATCHES/$project"/*.patch | sort); do
        echo "== apply $(basename "$patch") to $project"
        git -C "$project" apply --whitespace=nowarn "$patch"
    done
done

echo "== projects: $(repo --no-pager list | wc -l)"
