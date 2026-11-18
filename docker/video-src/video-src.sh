#!/bin/sh
# Live test pattern, Disco stream geometry (856x480, 30 fps), H.264 baseline,
# RTP payload type 96 with RTCP sender reports, to fake-sc2.
#
# Periodic intra refresh instead of periodic IDR frames: an IDR resets the
# H.264 frame_num to 0, which Parrot's receiver (libvideo-streaming,
# vstrm_rtp_h264_rx_check_missing_frames, "TODO: handle reset to 0 for IDR
# frame") reports as missing frames, and the decoder then hides frames until
# it resyncs. Parrot's drones don't stream periodic IDRs either. SPS/PPS are
# still repeated every second (config-interval=1) for receivers joining late.
# Env: HOST (127.0.0.1), RTP_PORT (55004), RTCP_PORT (55005),
#      PATTERN (smpte), BITRATE kbit/s (1500), LABEL (FAKE DISCO),
#      SPEED scroll in pixels per frame (4, 0 for a static pattern).
set -eu
HOST=${HOST:-127.0.0.1}
RTP_PORT=${RTP_PORT:-55004}
RTCP_PORT=${RTCP_PORT:-55005}
PATTERN=${PATTERN:-smpte}
BITRATE=${BITRATE:-1500}
LABEL=${LABEL:-FAKE DISCO}
# Pattern scrolls horizontally (pixels per frame), so every screen region
# changes between frames: video checks don't depend on the app's layout.
SPEED=${SPEED:-4}

exec gst-launch-1.0 -e \
    rtpbin name=rtpbin \
    videotestsrc is-live=true pattern="$PATTERN" horizontal-speed="$SPEED" \
    ! video/x-raw,width=856,height=480,framerate=30/1 \
    ! textoverlay text="$LABEL" valignment=top halignment=left font-desc="Sans Bold 28" \
    ! timeoverlay valignment=bottom halignment=right font-desc="Sans Bold 28" \
        time-mode=running-time \
    ! videoconvert \
    ! x264enc tune=zerolatency speed-preset=ultrafast bitrate="$BITRATE" \
        key-int-max=30 intra-refresh=true bframes=0 \
    ! video/x-h264,profile=baseline \
    ! rtph264pay pt=96 config-interval=1 mtu=1400 \
    ! rtpbin.send_rtp_sink_0 \
    rtpbin.send_rtp_src_0 ! udpsink host="$HOST" port="$RTP_PORT" \
    rtpbin.send_rtcp_src_0 ! udpsink host="$HOST" port="$RTCP_PORT" sync=false async=false
