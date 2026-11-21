#!/usr/bin/env bash
# Saves the simulated Disco camera's current frame (FlightGear's display)
# as a PNG: rig/scripts/sim-frame.sh <out.png>
set -eu
docker exec disco-fpv-fg gst-launch-1.0 -q ximagesrc display-name=:99 num-buffers=1 \
	! videoconvert ! pngenc ! filesink location=/tmp/frame.png
docker cp disco-fpv-fg:/tmp/frame.png "$1" >/dev/null
echo "$1"
