# Headless Chromium (Playwright's image, Node 22) with Mesa's GPU drivers
# (Vulkan and EGL), for the CesiumJS prototype of the simulator's camera
# (sim/cesium): without them Chromium falls back to SwiftShader (software).
FROM mcr.microsoft.com/playwright:v1.49.1-noble
RUN apt-get update && apt-get install -y --no-install-recommends \
        mesa-vulkan-drivers libvulkan1 libegl1 libgl1-mesa-dri vulkan-tools \
    && rm -rf /var/lib/apt/lists/*
