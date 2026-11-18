# Fake Disco camera: live H.264 test pattern sent as RTP/RTCP over UDP to
# fake-sc2 (127.0.0.1:55004 RTP, 55005 RTCP). Run with --network host.
FROM debian:bookworm-slim

ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        gstreamer1.0-tools gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
        gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly gstreamer1.0-x \
        fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

COPY video-src.sh /usr/local/bin/video-src
RUN chmod a+rx /usr/local/bin/video-src
# fontconfig needs a writable cache: give it /tmp
ENV XDG_CACHE_HOME=/tmp/cache
USER nobody
ENTRYPOINT ["/usr/local/bin/video-src"]
