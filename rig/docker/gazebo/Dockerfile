# Gazebo Harmonic with ArduPilot's plugin (ardupilot_gazebo): an
# alternative to FlightGear for the simulated Disco, with real 3D objects
# (trees, buildings) near the ground. Gazebo computes the flight physics of
# a flying wing (the plugin's Zephyr, close to a Disco) driven by ArduPilot
# SITL through its JSON interface, renders the nose camera (EGL, headless)
# and can show a line-of-sight view on the PC's screen (Gazebo's GUI).
# Gazebo is Apache 2.0, ardupilot_gazebo LGPL-3.0: separate programs.
FROM ubuntu:24.04

ARG ARDUPILOT_GAZEBO_COMMIT=f116b03552d05c8d0b0bed5ed67033a1183121d1
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl gnupg lsb-release git cmake build-essential \
    && curl -fsSL https://packages.osrfoundation.org/gazebo.gpg \
        -o /usr/share/keyrings/pkgs-osrf-archive-keyring.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/pkgs-osrf-archive-keyring.gpg] http://packages.osrfoundation.org/gazebo/ubuntu-stable noble main" \
        > /etc/apt/sources.list.d/gazebo-stable.list \
    && apt-get update && apt-get install -y --no-install-recommends \
        gz-harmonic libgz-sim8-dev rapidjson-dev libopencv-dev \
        libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev \
        gstreamer1.0-tools gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
        gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly gstreamer1.0-libav \
        mesa-utils libegl1 libgl1-mesa-dri xvfb \
    && rm -rf /var/lib/apt/lists/*

# gz-transport's CMake targets want cppzmq's
RUN apt-get update && apt-get install -y --no-install-recommends \
        cppzmq-dev libzmq3-dev libprotobuf-dev protobuf-compiler x264 \
    && rm -rf /var/lib/apt/lists/*

RUN git clone https://github.com/ArduPilot/ardupilot_gazebo /opt/ardupilot_gazebo \
    && cd /opt/ardupilot_gazebo && git checkout ${ARDUPILOT_GAZEBO_COMMIT} \
    && mkdir build && cd build \
    && cmake .. -DCMAKE_BUILD_TYPE=RelWithDebInfo && make -j"$(nproc)"

ENV GZ_VERSION=harmonic \
    GZ_PARTITION=disco-fpv \
    GZ_SIM_SYSTEM_PLUGIN_PATH=/opt/ardupilot_gazebo/build \
    GZ_SIM_RESOURCE_PATH=/opt/ardupilot_gazebo/models:/opt/ardupilot_gazebo/worlds:/sim/gazebo/models:/sim/gazebo/worlds
# the ground's textures (ambientCG, ambientcg.com, CC0): 1K colour maps,
# as rig/sim/bridge/src/gazebo-world.ts's TEXTURES names them
RUN apt-get update && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/disco-textures && cd /tmp \
    && for t in Grass004 Grass005 Grass001 Ground037 Ground048 Ground109 \
        Ground103 Asphalt031 Gravel043; do \
        curl -fsSL -o t.zip "https://ambientcg.com/get?file=${t}_1K-JPG.zip" || exit 1; \
        unzip -p t.zip "${t}_1K-JPG_Color.jpg" > /opt/disco-textures/$t.jpg || exit 1; \
        rm t.zip; \
    done \
    && chmod -R a+rX /opt/disco-textures
RUN useradd -m gz
USER gz
WORKDIR /home/gz
# 3D models of the world (Open Robotics on Gazebo Fuel, CC-BY 4.0), in the
# image so a run does not depend on Fuel being reachable
RUN for m in "Oak tree" "Pine Tree" "House 1" "House 2" "House 3" "Lamp Post" \
        "Office Building" "Water Tower" "Gas Station"; do \
        gz fuel download -u "https://fuel.gazebosim.org/1.0/OpenRobotics/models/$(echo "$m" | sed 's/ /%20/g')" || exit 1; \
    done
# readable by the line-of-sight view, which runs as the host user
USER root
RUN chmod -R a+rX /home/gz
ENV GZ_FUEL_CACHE_PATH=/home/gz/.gz/fuel
# sockets bound to every interface go to loopback (the container shares
# the host's network), except Gazebo's multicast discovery
COPY --from=loopback loopback-bind.c /tmp/
RUN gcc -shared -fPIC -O2 -o /usr/local/lib/loopback-bind.so /tmp/loopback-bind.c -ldl
ENV LD_PRELOAD=/usr/local/lib/loopback-bind.so LOOPBACK_BIND_KEEP=10317,10318
COPY --chmod=755 run-gazebo.sh /usr/local/bin/run-gazebo
COPY --chmod=755 sim-view.sh /usr/local/bin/sim-view
COPY --chmod=755 gz-helper.py /usr/local/bin/gz-helper
USER gz
CMD ["run-gazebo"]
