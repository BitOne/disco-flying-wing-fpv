#!/usr/bin/env bash
# Sync the Unified GroundSdk workspace into sdk/ (runs inside the sdk-build container).
#
# Reproducibility: the manifest repo is pinned to MANIFEST_REV. After the first
# sync, the exact revision of every project is written to sdk.lock.xml (committed).
# When sdk.lock.xml exists, later syncs use it instead of the manifest's default.
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

echo "== projects: $(repo --no-pager list | wc -l)"
