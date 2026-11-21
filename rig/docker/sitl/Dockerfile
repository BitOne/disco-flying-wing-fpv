# ArduPilot SITL, ArduPlane: flight dynamics and autopilot of the simulated
# Disco (flying wing, elevons). Built from a pinned release. Its sockets
# (MAVLink TCP 5760-5763, simulated RC input UDP 5501, ...) are bound to
# 127.0.0.1 by loopback-bind.so (the container shares the host's network):
# nobody on the LAN flies the simulated Disco.
# ArduPilot is GPLv3; it runs here as a separate program, not linked into
# the app.
FROM ubuntu:22.04

ARG ARDUPILOT_TAG=Plane-4.5.7
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        git ca-certificates python3 python3-pip python3-dev g++ make \
        pkg-config libxml2-dev libxslt1-dev \
    && rm -rf /var/lib/apt/lists/* \
    && pip3 install --no-cache-dir empy==3.3.4 pexpect future pymavlink==2.4.41 dronecan==1.0.26

WORKDIR /ardupilot
RUN git clone --depth 1 --branch ${ARDUPILOT_TAG} --recurse-submodules --shallow-submodules \
        https://github.com/ArduPilot/ardupilot.git . \
    && ./waf configure --board sitl \
    && ./waf plane \
    && rm -rf .git modules/*/.git

COPY --from=loopback loopback-bind.c /tmp/
RUN gcc -shared -fPIC -O2 -o /usr/local/lib/loopback-bind.so /tmp/loopback-bind.c -ldl
ENV LD_PRELOAD=/usr/local/lib/loopback-bind.so
COPY run-sitl.sh /usr/local/bin/run-sitl
RUN chmod a+rx /usr/local/bin/run-sitl
CMD ["run-sitl"]
