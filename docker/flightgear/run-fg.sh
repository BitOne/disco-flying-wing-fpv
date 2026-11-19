#!/usr/bin/env bash
# Headless FlightGear following ArduPilot SITL (native-fdm on UDP 5504,
# relayed at the frame rate by sim/bridge from SITL's 5503),
# viewed from the nose of an invisible aircraft (the camera), streamed as
# H.264 RTP to HOST:RTP_PORT (RTCP RTP_PORT+1), like docker/video-src.
# Rendered on the GPU through VirtualGL when the container has a render
# node; optional effects off and visibility limited to keep it light.
set -eu
WIDTH=${WIDTH:-1280}
HEIGHT=${HEIGHT:-720}
FPS=${FPS:-15}
HOST=${HOST:-127.0.0.1}
RTP_PORT=${RTP_PORT:-55004}
RTCP_PORT=${RTCP_PORT:-55005}
export DISPLAY=:99

Xvfb :99 -screen 0 ${WIDTH}x${HEIGHT}x24 -nolisten tcp &
sleep 2

# start where the simulated Disco is (HOME=lat,lon,...), so the scenery
# around it is loaded from the first frame
if [ -n "${HOME_LOC:-}" ]; then
	IFS=, read -r LAT LON _ <<<"$HOME_LOC"
	START_POS="--lat=$LAT --lon=$LON --altitude=50"
else
	START_POS="--airport=PHNL"
fi

# GPU rendering through VirtualGL when a render node is available
GL_WRAP=""
if [ -e "${GPU_DEVICE:-/dev/dri/card1}" ] && [ -z "${SOFTWARE_GL:-}" ]; then
	GL_WRAP="vglrun -d ${GPU_DEVICE:-/dev/dri/card1}"
	echo "rendering on the GPU (${GPU_DEVICE:-/dev/dri/card1}) through VirtualGL"
else
	echo "no GPU: software rendering (very slow with FlightGear's scenery)"
fi

$GL_WRAP fgfs --fdm=external --native-fdm=socket,in,${FPS},,${FDM_PORT:-5504},udp \
	--aircraft=ufo ${START_POS} \
	--geometry=${WIDTH}x${HEIGHT} --bpp=24 --disable-fullscreen \
	--timeofday=noon --disable-sound --disable-hud --disable-hud-3d \
	--disable-panel --disable-clouds --fog-fastest --disable-random-objects \
	--disable-ai-traffic --disable-ai-models --disable-real-weather-fetch \
	--disable-terrasync --disable-splash-screen --disable-specular-highlight \
	--prop:/sim/rendering/shaders/quality-level=0 \
	--disable-clouds3d --prop:/sim/rendering/clouds3d-enable=false \
	--prop:/sim/rendering/random-vegetation=false \
	--prop:/sim/rendering/random-objects=false \
	--prop:/sim/rendering/random-buildings=false \
	--prop:/sim/rendering/shaders/skydome=false \
	--prop:/sim/rendering/particles=false \
	--visibility=${VISIBILITY:-12000} \
	--prop:/sim/rendering/multi-sample-buffer=false \
	--prop:/sim/current-view/field-of-view=${FOV:-80} \
	--prop:/sim/frame-rate-throttle-hz=${FPS} \
	--prop:/sim/menubar/visibility=false \
	${FG_ARGS:-} &
FG=$!

# camera: the X display, as the Disco's H.264 stream
sleep ${CAPTURE_DELAY:-20}
exec gst-launch-1.0 -q rtpbin name=rtp \
	ximagesrc display-name=:99 use-damage=false show-pointer=false \
	! video/x-raw,framerate=${FPS}/1 ! videoconvert \
	! video/x-raw,format=I420 \
	! x264enc tune=zerolatency speed-preset=ultrafast bitrate=${BITRATE:-2500} \
		key-int-max=${FPS} intra-refresh=true \
	! video/x-h264,profile=baseline \
	! rtph264pay config-interval=1 pt=96 mtu=1400 \
	! rtp.send_rtp_sink_0 \
	rtp.send_rtp_src_0 ! udpsink host=${HOST} port=${RTP_PORT} sync=false async=false \
	rtp.send_rtcp_src_0 ! udpsink host=${HOST} port=${RTCP_PORT} sync=false async=false
