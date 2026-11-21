# Headless Android emulator (x86_64, KVM) for end-to-end tests.
#
# Run with --device /dev/kvm and --network host: inside the emulator, 10.0.2.2
# is the container's loopback, which with host networking is the host's, so
# the app reaches the fake SkyController 2 running on the host.

FROM ubuntu:22.04

ARG DEBIAN_FRONTEND=noninteractive
ARG UID=1000
ARG GID=1000

ARG CMDLINE_TOOLS_ZIP=commandlinetools-linux-11076708_latest.zip
ARG API=35
ARG SYSTEM_IMAGE="system-images;android-35;google_apis;x86_64"

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl unzip openjdk-17-jdk-headless \
        libpulse0 libnss3 libxcomposite1 libxcursor1 libxdamage1 libxi6 \
        libxrandr2 libxtst6 libasound2 libgl1 libegl1 libxkbfile1 \
        libdbus-1-3 libfontconfig1 libx11-xcb1 libxkbcommon0 \
        procps python3 \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/emulator:${ANDROID_HOME}/platform-tools:${PATH}

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && curl -fsSL https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP} -o /tmp/ct.zip \
    && unzip -q /tmp/ct.zip -d /tmp/ct \
    && mv /tmp/ct/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm -rf /tmp/ct /tmp/ct.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install "platform-tools" "emulator" "platforms;android-${API}" "${SYSTEM_IMAGE}" > /dev/null \
    && chmod -R a+rX ${ANDROID_HOME}

# User matching the host user. Access to /dev/kvm is given at run time with
# --group-add <host kvm gid>.
RUN groupadd -g ${GID} builder \
    && useradd -m -u ${UID} -g ${GID} -s /bin/bash builder
USER builder

# AVD (created at image build so the first boot only needs the cold start)
ENV ANDROID_AVD_HOME=/home/builder/.android/avd
RUN echo no | avdmanager create avd -n disco-test -k "${SYSTEM_IMAGE}" -d pixel_6 \
    && printf 'hw.keyboard=yes\nhw.gpu.enabled=yes\nhw.gpu.mode=swiftshader_indirect\nhw.ramSize=3072\ndisk.dataPartition.size=4G\n' \
        >> ${ANDROID_AVD_HOME}/disco-test.avd/config.ini

WORKDIR /work
