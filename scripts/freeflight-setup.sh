#!/usr/bin/env bash
# FreeFlight Pro validation setup (TASKS.md M1b): runs the fake Disco inside
# the emulator over Wi-Fi, announces it over mDNS, starts the fake camera on
# the host, installs and launches FreeFlight.
#
# usage: freeflight-setup.sh <emu-container> <out-dir> [freeflight.apk]
#
# The FreeFlight APK is not redistributed: pass the path of your own copy
# (FREEFLIGHT_APK). Without it, an already installed FreeFlight is used.
set -euo pipefail

EMU=$1
OUT=$2
FF_APK=${3:-}
ADB="docker exec $EMU adb -s emulator-5554"
FAKE_BIN=sdk/out/groundsdk-android/x86_64/staging/usr/bin/fake-sc2
HELPER_APK=$OUT/fakedisco-debug.apk
# Ports the fake Disco reads the fake camera's RTP/RTCP on (guest side,
# redirected from the host with "adb emu redir").
VIDEO_RTP=56004
VIDEO_RTCP=56005
FF_PKG=com.parrot.freeflight3

for f in "$FAKE_BIN" "$OUT/libc++_shared.so" "$HELPER_APK"; do
	[ -f "$f" ] || { echo "missing $f (make fake-android freeflight-helper)" >&2; exit 1; }
done

# Other GroundSdk apps would auto-connect to the in-emulator Disco and
# hold its single controller slot.
for p in io.github.bitone.discofpv com.parrot.drone.groundsdkdemo $FF_PKG; do
	$ADB shell am force-stop $p || true
done

# fake Disco inside the emulator
$ADB shell "kill \$(pidof fake-sc2) 2>/dev/null" || true
docker cp "$FAKE_BIN" "$EMU":/tmp/fake-sc2
docker cp "$OUT/libc++_shared.so" "$EMU":/tmp/libc++_shared.so
$ADB push /tmp/fake-sc2 /tmp/libc++_shared.so /data/local/tmp/ >/dev/null
$ADB shell chmod 755 /data/local/tmp/fake-sc2
# Started from a script on the device: "adb shell" with an inline
# background job never returns.
cat > "$OUT/start-fake-disco.sh" <<EOF
cd /data/local/tmp
export LD_LIBRARY_PATH=. ULOG_LEVEL=D ULOG_STDERR=y
nohup ./fake-sc2 --disco --video-port $VIDEO_RTP > fake.log 2>&1 < /dev/null &
EOF
docker cp "$OUT/start-fake-disco.sh" "$EMU":/tmp/start-fake-disco.sh
$ADB push /tmp/start-fake-disco.sh /data/local/tmp/ >/dev/null
$ADB shell sh /data/local/tmp/start-fake-disco.sh
sleep 2
$ADB shell pidof fake-sc2 >/dev/null || { $ADB shell cat /data/local/tmp/fake.log; exit 1; }

# host -> guest: fake camera RTP/RTCP; host -> guest TCP: control socket
$ADB emu redir del udp:$VIDEO_RTP >/dev/null 2>&1 || true
$ADB emu redir del udp:$VIDEO_RTCP >/dev/null 2>&1 || true
$ADB emu redir add udp:$VIDEO_RTP:$VIDEO_RTP >/dev/null
$ADB emu redir add udp:$VIDEO_RTCP:$VIDEO_RTCP >/dev/null
$ADB forward tcp:4422 tcp:4322 >/dev/null

# mDNS announce (Android NSD, through the helper app)
docker cp "$HELPER_APK" "$EMU":/tmp/fakedisco.apk
$ADB install -r -g /tmp/fakedisco.apk >/dev/null
$ADB shell am start -n io.github.bitone.discofpv.fakedisco/.PublishActivity >/dev/null

# FreeFlight
if [ -n "$FF_APK" ]; then
	docker cp "$FF_APK" "$EMU":/tmp/ff.apk
	$ADB install -r -g /tmp/ff.apk >/dev/null
fi
$ADB shell pm path $FF_PKG >/dev/null || { echo "FreeFlight not installed: set FREEFLIGHT_APK" >&2; exit 1; }
sleep 3
$ADB shell monkey -p $FF_PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
echo "fake Disco in emulator, video expected on host UDP $VIDEO_RTP/$VIDEO_RTCP, control on tcp:4422"
