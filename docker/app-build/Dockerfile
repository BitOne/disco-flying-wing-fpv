# Build environment for our app (app/): modern toolchain, separate from the
# SDK's 2020 toolchain (docker/sdk-build). The SDK is consumed as AARs
# (make sdk-aar).
FROM eclipse-temurin:17-jdk-jammy

ARG DEBIAN_FRONTEND=noninteractive
ARG UID=1000
ARG GID=1000
ARG CMDLINE_TOOLS_ZIP=commandlinetools-linux-11076708_latest.zip
ARG GRADLE_VERSION=8.11.1

RUN apt-get update && apt-get install -y --no-install-recommends unzip curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_HOME=/opt/gradle
ENV PATH=${GRADLE_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && curl -fsSL https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP} -o /tmp/ct.zip \
    && unzip -q /tmp/ct.zip -d /tmp/ct && mv /tmp/ct/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm -rf /tmp/ct /tmp/ct.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0" > /dev/null \
    && chmod -R a+rX ${ANDROID_HOME}

RUN curl -fsSL https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt && mv /opt/gradle-${GRADLE_VERSION} ${GRADLE_HOME} \
    && rm /tmp/gradle.zip

# Host-matching user; volume-backed dirs pre-created so volumes are user-owned.
RUN (getent group ${GID} || groupadd -g ${GID} builder) \
    && useradd -m -u ${UID} -g ${GID} -s /bin/bash builder \
    && mkdir -p /home/builder/.gradle /home/builder/.android \
    && chown -R ${UID}:${GID} /home/builder
USER builder
WORKDIR /work
