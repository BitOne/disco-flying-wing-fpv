#!/usr/bin/env bash
# Puts the emulator in "real USB" mode: the app reaches the fake
# SkyController 2 through Android's USB accessory path (UsbManager), as on
# a phone plugged into the controller, instead of the debug TCP bridge.
#
# How: the emulator kernel has dummy_hcd (a virtual USB device controller,
# dummy_udc.0, wired to a virtual host controller) and the accessory gadget
# function. The configfs gadget g1 gets the accessory function and is bound
# to dummy_udc.0, so the phone shows up as a USB device on its own host
# controller; aoa-host (in the emulator, root) plays the SkyController: AOA
# handshake as "Parrot" / "Skycontroller 2", then relays the accessory's
# bulk endpoints to fake-sc2's mux port on the host. Android only handles
# accessories when the android.hardware.usb.accessory feature is declared,
# which the emulator image doesn't: it is added to /system (emulator started
# with -writable-system, see EMU_MODE_ARGS) and Android rebooted, once per
# emulator start.
#
# usage: emu-usb-setup.sh <emu-container> <aoa-host binary> [mux port]
set -euo pipefail
EMU=$1
AOA_HOST=$2
PORT=${3:-4321}
ADB="docker exec $EMU adb -s emulator-5554"

wait_boot() {
	$ADB wait-for-device
	until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
}

$ADB root >/dev/null; sleep 2; $ADB wait-for-device
if ! $ADB shell pm list features | grep -q android.hardware.usb.accessory; then
	# a feature file is read at boot
	echo "declaring android.hardware.usb.accessory (remount, reboot)"
	# the first remount sets up overlayfs and asks for a reboot
	if $ADB remount 2>&1 | grep -qi reboot; then
		$ADB reboot; wait_boot; $ADB root >/dev/null; sleep 2; $ADB wait-for-device
		$ADB remount >/dev/null
	fi
	docker cp "$(dirname "$0")/../aoa-host/android.hardware.usb.accessory.xml" "$EMU":/tmp/usb-accessory.xml
	$ADB push /tmp/usb-accessory.xml /system/etc/permissions/android.hardware.usb.accessory.xml >/dev/null
	$ADB reboot
	wait_boot
	$ADB root >/dev/null; sleep 2; $ADB wait-for-device
	$ADB shell pm list features | grep -q android.hardware.usb.accessory || { echo "feature not declared" >&2; exit 1; }
fi

# gadget: accessory function bound to the dummy device controller
$ADB shell 'cd /config/usb_gadget/g1 &&
	mkdir -p functions/accessory.gs2 configs/b.1/strings/0x409 strings/0x409 &&
	echo 0x18d1 > idVendor && echo 0x2d01 > idProduct &&
	echo Google > strings/0x409/manufacturer && echo "Android emulator" > strings/0x409/product &&
	echo EMULATOR0001 > strings/0x409/serialnumber &&
	echo accessory > configs/b.1/strings/0x409/configuration &&
	{ [ -e configs/b.1/f1 ] || ln -s functions/accessory.gs2 configs/b.1/f1; } &&
	{ [ "$(cat UDC)" = dummy_udc.0 ] || echo dummy_udc.0 > UDC; }'

# the fake controller's USB side
docker cp "$AOA_HOST" "$EMU":/tmp/aoa-host
$ADB push /tmp/aoa-host /data/local/tmp/aoa-host >/dev/null
$ADB shell chmod 755 /data/local/tmp/aoa-host
$ADB shell "printf 'cd /data/local/tmp\npkill aoa-host\nnohup ./aoa-host --port $PORT > aoa-host.log 2>&1 < /dev/null &\n' > /data/local/tmp/start-aoa.sh"
$ADB shell sh /data/local/tmp/start-aoa.sh
sleep 3
$ADB shell cat /data/local/tmp/aoa-host.log

# First plug on a fresh emulator: Android asks which app handles the
# controller ("Open Disco FPV to handle ...?"), or the app asks for the
# accessory permission. Accept once with "Always", as a pilot does.
if [ "${ACCEPT_DIALOG:-1}" = 1 ]; then
	tap_text() { # taps the centre of the first element whose text contains $1
		local xml b
		xml=$($ADB exec-out uiautomator dump /dev/tty 2>/dev/null || true)
		b=$(printf '%s' "$xml" | grep -o "text=\"[^\"]*$1[^\"]*\"[^>]*bounds=\"[^\"]*\"" | head -n 1 |
			sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
		[ -n "$b" ] || return 1
		set -- $b
		$ADB shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
	}
	for _ in $(seq 1 15); do
		if tap_text "Always" ; then
			sleep 1
			tap_text "OK" && echo "accepted the controller for the app" && break
		fi
		sleep 2
	done
fi
