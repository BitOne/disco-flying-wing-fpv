# FlightGear as a pure viewer of the simulated flight (native-fdm from
# ArduPilot SITL, relayed by rig/sim/bridge), rendered headless and sent as the
# Disco's camera: GStreamer captures the display and streams H.264 RTP like
# the fake camera (rig/docker/video-src).
#
# Rendering: on the host GPU when one is given to the container
# (--device /dev/dri/renderD128): VirtualGL's EGL back end renders
# FlightGear's OpenGL on the GPU and draws the frames into an Xvfb window.
# Without a GPU, Mesa's software rasterizer is far too slow for FlightGear's
# scenery (minutes per frame). Debian trixie for Mesa 25 (recent Intel GPUs).
# FlightGear is GPLv2 and VirtualGL LGPL; they run here as separate programs.
# Scenery: Debian trixie's repacked data (2020.3.19+dfsg) has no terrain;
# bookworm's (2020.3.17, same format) ships Hawaii and Iceland. Taken from
# there.
FROM debian:bookworm-slim AS scenery
RUN apt-get update && apt-get install -y --no-install-recommends flightgear-data-base \
    && rm -rf /var/lib/apt/lists/*

FROM debian:trixie-slim

ARG VIRTUALGL_VERSION=3.1.3
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl \
        flightgear flightgear-data-base flightgear-data-ai \
        xvfb mesa-utils libgl1-mesa-dri libegl-mesa0 libegl1 libglu1-mesa \
        gstreamer1.0-tools gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
        gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly gstreamer1.0-x \
    && curl -fsSL -o /tmp/vgl.deb \
        https://github.com/VirtualGL/virtualgl/releases/download/${VIRTUALGL_VERSION}/virtualgl_${VIRTUALGL_VERSION}_amd64.deb \
    && apt-get install -y --no-install-recommends /tmp/vgl.deb \
    && rm -rf /var/lib/apt/lists/* /tmp/vgl.deb

COPY --from=scenery /usr/share/games/flightgear/Scenery /usr/share/games/flightgear/Scenery
RUN useradd -m fg && mkdir -p /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix
ENV PATH=/usr/games:/opt/VirtualGL/bin:$PATH
# no session bus in the container: FlightGear asks D-Bus to inhibit the
# screensaver, which asserts without one; keep that non-fatal
ENV DBUS_FATAL_WARNINGS=0
COPY run-fg.sh /usr/local/bin/run-fg
RUN chmod a+rx /usr/local/bin/run-fg
USER fg
CMD ["run-fg"]
