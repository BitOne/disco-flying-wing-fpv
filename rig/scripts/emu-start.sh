#!/usr/bin/env bash
# Start the headless emulator container (detached) and wait for Android to boot.
# Usage: rig/scripts/emu-start.sh <image> <container-name> <out-dir>
# EMU_MODE_ARGS replaces -read-only, e.g. "-writable-system" for the USB
# accessory mode (rig/scripts/emu-usb-setup.sh).
set -euo pipefail
IMAGE=$1; NAME=$2; OUT=$3
BOOT_TIMEOUT=${BOOT_TIMEOUT:-300}
KVM_GID=$(stat -c %g /dev/kvm)

MODE=${EMU_MODE_ARGS:--read-only}

mkdir -p "$OUT"
# an emulator left in the other mode is restarted: a USB-mode one keeps its
# aoa-host relay connected to the fake, and a debug build would then reach
# the fake twice (USB and debug bridge), the two connections fighting
if docker ps --format '{{.Names}}' | grep -qx "$NAME" &&
   [ "$(docker inspect -f '{{index .Config.Labels "disco-fpv.emu-mode"}}' "$NAME" 2>/dev/null)" != "$MODE" ]; then
    echo "emulator container $NAME is in another mode: restarting it"
    docker rm -f "$NAME" >/dev/null
fi
if docker ps --format '{{.Names}}' | grep -qx "$NAME"; then
    echo "emulator container $NAME already running"
else
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker run -d --name "$NAME" --network host --label "disco-fpv.emu-mode=$MODE" \
        --device /dev/kvm --group-add "$KVM_GID" \
        -v "$OUT":/out \
        "$IMAGE" \
        emulator -avd disco-test -no-window -no-audio -no-boot-anim \
            -gpu "${EMU_GPU:-swiftshader_indirect}" -no-snapshot -accel on $MODE \
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
docker exec "$NAME" adb -s emulator-5554 shell 'settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0; svc power stayon true; settings put secure immersive_mode_confirmations confirmed; settings put global hide_error_dialogs 1; input keyevent 82' >/dev/null
