#!/usr/bin/env bash
# Simulated Disco in Gazebo: the physics of the flying wing (driven by
# ArduPilot SITL started with SITL_MODEL=JSON:127.0.0.1) and its nose
# camera, rendered headless on the GPU (EGL) and streamed as H.264 RTP to
# HOST:RTP_PORT (RTCP RTP_PORT+1), like rig/docker/video-src and FlightGear.
# The latest frame is also kept, once a second, in /tmp/camera.jpg.
# WORLD: the SDF world (rig/sim/gazebo/worlds/disco.sdf, mounted at /sim).
set -eu
WORLD=${WORLD:-/sim/gazebo/worlds/disco.sdf}
FPS=${FPS:-30}
HOST=${HOST:-127.0.0.1}
RTP_PORT=${RTP_PORT:-55004}
RTCP_PORT=${RTCP_PORT:-55005}
CAMERA_TOPIC=${CAMERA_TOPIC:-/world/disco/model/disco_zephyr/link/camera_link/sensor/camera/image}

gz sim -s -r --headless-rendering -v ${GZ_VERBOSE:-3} "$WORLD" &
GZ=$!

# the bridge's commands (the hand launch)
gz-helper &

# the camera's stream starts once asked for, when its topic exists
for _ in $(seq 120); do
	gz topic -l 2>/dev/null | grep -qx "$CAMERA_TOPIC/enable_streaming" && break
	sleep 1
done
gz topic -t "$CAMERA_TOPIC/enable_streaming" -m gz.msgs.Boolean -p "data: 1"
echo "camera streaming enabled"

# GstCameraPlugin's RTP (127.0.0.1:5600) re-encoded like the Disco's
# stream: intra refresh, no B frames, with RTCP for fake-sc2's receiver
gst-launch-1.0 -q rtpbin name=rtp \
	udpsrc port=${GZ_CAMERA_PORT:-5600} \
		caps='application/x-rtp,media=video,clock-rate=90000,encoding-name=H264' \
	! rtpjitterbuffer latency=0 ! rtph264depay ! avdec_h264 ! videoconvert \
	! video/x-raw,format=I420 ! tee name=t \
	t. ! queue leaky=downstream max-size-buffers=1 ! videorate drop-only=true \
		! video/x-raw,framerate=1/1 ! jpegenc \
		! multifilesink location=/tmp/camera.jpg sync=false async=false \
	t. ! queue leaky=downstream max-size-buffers=2 \
	! x264enc tune=zerolatency speed-preset=ultrafast bitrate=${BITRATE:-2500} \
		key-int-max=${FPS} intra-refresh=true \
	! video/x-h264,profile=baseline \
	! rtph264pay config-interval=1 pt=96 mtu=1400 \
	! rtp.send_rtp_sink_0 \
	rtp.send_rtp_src_0 ! udpsink host=${HOST} port=${RTP_PORT} sync=false async=false \
	rtp.send_rtcp_src_0 ! udpsink host=${HOST} port=${RTCP_PORT} sync=false async=false &

wait $GZ
