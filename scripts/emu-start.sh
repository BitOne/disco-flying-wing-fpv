#!/usr/bin/env bash
# Start the headless emulator container (detached) and wait for Android to boot.
# Usage: scripts/emu-start.sh <image> <container-name> <out-dir>
set -euo pipefail
IMAGE=$1; NAME=$2; OUT=$3
BOOT_TIMEOUT=${BOOT_TIMEOUT:-300}
KVM_GID=$(stat -c %g /dev/kvm)

mkdir -p "$OUT"
if docker ps --format '{{.Names}}' | grep -qx "$NAME"; then
    echo "emulator container $NAME already running"
else
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker run -d --name "$NAME" --network host \
        --device /dev/kvm --group-add "$KVM_GID" \
        -v "$OUT":/out \
        "$IMAGE" \
        emulator -avd disco-test -no-window -no-audio -no-boot-anim \
            -gpu swiftshader_indirect -no-snapshot -accel on -read-only \
            -ports 5554,5555 >/dev/null
    echo "started emulator container $NAME"
fi

start=$(date +%s)
until [ "$(docker exec "$NAME" adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    if ! docker ps --format '{{.Names}}' | grep -qx "$NAME"; then
        echo "emulator container exited; last logs:" >&2
        docker logs --tail 40 "$NAME" >&2 || true
        exit 1
    fi
    if [ $(( $(date +%s) - start )) -ge "$BOOT_TIMEOUT" ]; then
        echo "emulator did not boot within ${BOOT_TIMEOUT}s; last logs:" >&2
        docker logs --tail 40 "$NAME" >&2
        exit 1
    fi
    sleep 3
done
echo "emulator booted in $(( $(date +%s) - start ))s"
# Keep the screen on and skip animations: steadier screenshots and UI tests.
docker exec "$NAME" adb -s emulator-5554 shell 'settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0; svc power stayon true; input keyevent 82' >/dev/null
