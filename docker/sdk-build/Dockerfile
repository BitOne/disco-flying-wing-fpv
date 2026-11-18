# Build environment for Unified GroundSdk (Parrot GroundSDK fork) on Android.
#
# Versions follow the fork's build configuration:
#   - NDK r21e (21.4.7075529): groundsdk-product pins it, supported range r17b..r22
#   - Android Gradle Plugin 3.6.1 / Gradle 6.2.2: needs JDK 8 to build
#   - compileSdk/targetSdk 28, build-tools 28.0.3 (AGP 3.6 default)
# The Android cmdline-tools (sdkmanager) need JDK 17, used only at image build time.

FROM ubuntu:20.04

ARG DEBIAN_FRONTEND=noninteractive
ARG UID=1000
ARG GID=1000

ARG CMDLINE_TOOLS_ZIP=commandlinetools-linux-11076708_latest.zip
ARG NDK_VERSION=21.4.7075529
ARG PLATFORM=android-28
ARG BUILD_TOOLS=28.0.3

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl unzip zip xz-utils file \
        git make pkg-config build-essential \
        python2 python3 python3-pip python3-distutils \
        openjdk-8-jdk-headless openjdk-17-jdk-headless \
        rsync locales sudo \
    && rm -rf /var/lib/apt/lists/* \
    && locale-gen en_US.UTF-8

ENV LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8 \
    JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_SDK_PATH=/opt/android-sdk \
    ANDROID_NDK_PATH=/opt/android-sdk/ndk/${NDK_VERSION} \
    ANDROID_NDK_HOME=/opt/android-sdk/ndk/${NDK_VERSION}

# Google's repo tool
RUN curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo -o /usr/local/bin/repo \
    && chmod a+rx /usr/local/bin/repo

# Android SDK: cmdline-tools, platform, build-tools, NDK
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && curl -fsSL https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP} -o /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools \
    && mv /tmp/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip \
    && export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    && yes | ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null \
    && ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager --install \
        "platform-tools" \
        "platforms;${PLATFORM}" \
        "build-tools;${BUILD_TOOLS}" \
        "ndk;${NDK_VERSION}" > /dev/null \
    && chmod -R a+rX ${ANDROID_HOME}

# JDK 8 first on PATH (installing JDK 17 made it the system default).
ENV PATH=${JAVA_HOME}/bin:${ANDROID_NDK_PATH}:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/${BUILD_TOOLS}:${PATH}

# less: Parrot's build tool pages the Ground SDK EULA with it (make sdk-eula).
RUN apt-get update && apt-get install -y --no-install-recommends less \
    && rm -rf /var/lib/apt/lists/*

# Non-root user matching the host user, so files in the mounted workspace keep
# the host ownership. repo needs a git identity; a neutral one is set here.
# The dragon_build config dir is pre-created so its Docker volume is user-owned.
RUN groupadd -g ${GID} builder \
    && useradd -m -u ${UID} -g ${GID} -s /bin/bash builder \
    && echo "builder ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/builder \
    && mkdir -p /home/builder/.config/dragon_build /home/builder/.cache /home/builder/.gradle \
    && chown -R builder:builder /home/builder
USER builder
RUN git config --global user.name "disco-fpv builder" \
    && git config --global user.email "builder@localhost" \
    && git config --global color.ui false

WORKDIR /work
