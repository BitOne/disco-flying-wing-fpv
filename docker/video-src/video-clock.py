#!/usr/bin/env python3
# The fake camera (video-src.sh) with the wall clock drawn in each frame, for
# measuring glass-to-glass latency: a row of 26 square blocks in the middle
# of the picture, a white and a black reference, then the time in
# milliseconds modulo 2^24 (24 bits, most significant first, white = 1),
# taken when the frame leaves the test source. tests/latency/clock.ts reads
# it back from a screenshot. Same stream otherwise; same environment.
import os
import time

import gi

gi.require_version("Gst", "1.0")
from gi.repository import GLib, Gst  # noqa: E402

W, H = 856, 480
# CLOCK_BLOCK: block size in pixels; CLOCK_Y: top of the row. Default below
# the middle, clear of most HUD centres; for a headset view's lens
# distortion, small blocks at the centre (CLOCK_BLOCK=12 CLOCK_Y=234),
# where lines stay straight and widths even.
BLOCK = int(os.environ.get("CLOCK_BLOCK", "28"))
BITS = 24
X0 = (W - (BITS + 2) * BLOCK) // 2
Y0 = int(os.environ.get("CLOCK_Y", "300"))

env = os.environ.get
pipeline = f"""
    rtpbin name=rtpbin
    videotestsrc is-live=true pattern={env('PATTERN', 'smpte')} horizontal-speed={env('SPEED', '4')}
    ! video/x-raw,format=I420,width={W},height={H},framerate=30/1
    ! textoverlay text="{env('LABEL', 'FAKE DISCO')}" valignment=top halignment=left font-desc="Sans Bold 28"
    ! timeoverlay valignment=bottom halignment=right font-desc="Sans Bold 28" time-mode=running-time
    ! video/x-raw,format=I420
    ! appsink name=raw emit-signals=true sync=false async=false max-buffers=2 drop=true
    appsrc name=coded is-live=true format=time
        caps=video/x-raw,format=I420,width={W},height={H},framerate=30/1
    ! x264enc tune=zerolatency speed-preset=ultrafast bitrate={env('BITRATE', '1500')}
        key-int-max=30 intra-refresh=true bframes=0
    ! video/x-h264,profile=baseline
    ! rtph264pay pt=96 config-interval=1 mtu=1400
    ! rtpbin.send_rtp_sink_0
    rtpbin.send_rtp_src_0 ! udpsink host={env('HOST', '127.0.0.1')} port={env('RTP_PORT', '55004')} async=false
    rtpbin.send_rtcp_src_0 ! udpsink host={env('HOST', '127.0.0.1')} port={env('RTCP_PORT', '55005')}
        sync=false async=false
"""

WHITE = bytes([235]) * BLOCK
BLACK = bytes([16]) * BLOCK


def stamp(frame: bytearray):
    """Draws the time into the Y plane (first in I420, W bytes a row)."""
    ms = int(time.time() * 1000) % (1 << BITS)
    blocks = [1, 0] + [(ms >> (BITS - 1 - i)) & 1 for i in range(BITS)]
    for i, bit in enumerate(blocks):
        row = WHITE if bit else BLACK
        x = X0 + i * BLOCK
        for r in range(Y0, Y0 + BLOCK):
            frame[r * W + x:r * W + x + BLOCK] = row


def main():
    Gst.init(None)
    p = Gst.parse_launch(pipeline)
    coded = p.get_by_name("coded")

    # the source's buffers are read-only: each frame is copied, stamped and
    # pushed on to the encoder (both sinks async=false: the appsink must not
    # wait for the RTP branch to preroll, which waits for the appsink)
    def on_sample(sink):
        sample = sink.emit("pull-sample")
        src = sample.get_buffer()
        frame = bytearray(src.extract_dup(0, src.get_size()))
        stamp(frame)
        out = Gst.Buffer.new_wrapped(bytes(frame))
        out.pts, out.dts, out.duration = src.pts, src.dts, src.duration
        coded.emit("push-buffer", out)
        return Gst.FlowReturn.OK

    p.get_by_name("raw").connect("new-sample", on_sample)
    loop = GLib.MainLoop()
    bus = p.get_bus()
    bus.add_signal_watch()

    def on_message(_bus, msg):
        if msg.type in (Gst.MessageType.ERROR, Gst.MessageType.EOS):
            print(msg.parse_error() if msg.type == Gst.MessageType.ERROR else "EOS", flush=True)
            loop.quit()

    bus.connect("message", on_message)
    p.set_state(Gst.State.PLAYING)
    print(f"streaming with the clock code to {env('HOST', '127.0.0.1')}:{env('RTP_PORT', '55004')}", flush=True)
    try:
        loop.run()
    finally:
        p.set_state(Gst.State.NULL)


main()
