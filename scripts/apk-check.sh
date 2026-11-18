#!/usr/bin/env bash
# Release checks on an APK (run in the SDK image, which has the NDK):
# every arm64-v8a native library must have its LOAD segments aligned to
# 16 KB or more, for Android 15+ devices with 16 KB memory pages.
# x86_64 libraries are only for the emulator and are not checked.
#
# usage: apk-check.sh <apk> <llvm-readelf>
set -euo pipefail

APK=$1
READELF=$2
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
unzip -q -o "$APK" 'lib/arm64-v8a/*' -d "$work"

fail=0
for so in "$work"/lib/arm64-v8a/*.so; do
	min=$("$READELF" -lW "$so" | awk '/LOAD/ { print $NF }' | sort -u |
		while read -r a; do printf '%d\n' "$a"; done | sort -n | head -n 1)
	name=$(basename "$so")
	if [ "$min" -lt 16384 ]; then
		echo "FAIL $name: LOAD alignment $min" >&2
		fail=1
	else
		echo "ok   $name: LOAD alignment $min"
	fi
done
exit $fail
