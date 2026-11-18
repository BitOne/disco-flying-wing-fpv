# disco-flying-wing-fpv: single entry points for building and testing.
# See PLAN.md section 3. Targets not implemented yet fail with the task id
# that will implement them, so `make check` can never pass by accident.

SHELL := /bin/bash
.DEFAULT_GOAL := help

ROOT        := $(abspath .)
OUT         := $(ROOT)/out
RUN_ID      ?= $(shell date +%Y%m%d-%H%M%S)
RUN_DIR     := $(OUT)/$(RUN_ID)

SDK_IMAGE   := disco-fpv/sdk-build:1
UID         := $(shell id -u)
GID         := $(shell id -g)

# Run a command inside the SDK build container, with the project mounted at /work
# and a persistent cache volume for repo, Gradle and ccache data.
# The dragon_build volume keeps the Parrot EULA acceptance marker (make sdk-eula).
SDK_VOLUMES = \
	-v "$(ROOT)":/work \
	-v disco-fpv-cache:/home/builder/.cache \
	-v disco-fpv-gradle:/home/builder/.gradle \
	-v disco-fpv-dragon:/home/builder/.config/dragon_build
SDK_RUN = docker run --rm $(SDK_VOLUMES) -w /work $(SDK_IMAGE)

define not_implemented
	@echo "make $@: not implemented yet ($(1), see TASKS.md)" >&2; exit 1
endef

.PHONY: help
help: ## List targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  %-16s %s\n", $$1, $$2}'

# --- SDK build image -------------------------------------------------------

.PHONY: sdk-image
sdk-image: ## Build the Docker image used to build the SDK
	docker build \
		--build-arg UID=$(UID) --build-arg GID=$(GID) \
		-t $(SDK_IMAGE) docker/sdk-build

.PHONY: sdk-image-check
sdk-image-check: sdk-image ## Smoke-test the SDK image (repo, NDK, Java, Android SDK)
	$(SDK_RUN) bash -c 'set -eo pipefail; \
		repo version | grep -q "repo launcher version" && echo "ok   repo launcher"; \
		ndk-build --version | grep -q "GNU Make" && echo "ok   ndk-build"; \
		grep -q "Pkg.Revision = 21.4.7075529" $$ANDROID_NDK_PATH/source.properties && echo "ok   NDK 21.4.7075529"; \
		java -version 2>&1 | grep -q "\"1\.8\." && echo "ok   java 8"; \
		python3 --version && python2 --version 2>&1; \
		test -d $$ANDROID_HOME/platforms/android-28 && echo "ok   platform android-28"; \
		test -d $$ANDROID_HOME/build-tools/28.0.3 && echo "ok   build-tools 28.0.3"; \
		echo "sdk image OK"'

.PHONY: sdk-shell
sdk-shell: ## Open a shell in the SDK build container
	docker run --rm -it $(SDK_VOLUMES) -w /work $(SDK_IMAGE) bash

# Parrot's build tool shows the Ground SDK EULA and asks for acceptance on its
# first run, then stores a marker (kept in the disco-fpv-dragon volume).
# Interactive on purpose: the user reads and accepts it, not the agent.
.PHONY: sdk-eula
sdk-eula: sdk-image ## Read and accept Parrot's Ground SDK EULA (interactive, run it yourself)
	docker run --rm -it $(SDK_VOLUMES) -w /work/sdk $(SDK_IMAGE) \
		./build.sh -p groundsdk-android -l

# --- Main entry points (PLAN.md section 3) --------------------------------

.PHONY: sdk-sync
sdk-sync: ## Check out the GroundSdk fork into sdk/ (pinned by sdk.lock.xml)
	$(SDK_RUN) scripts/sdk-sync.sh

# ABIs we need: x86_64 for the emulator, arm64-v8a for the Fairphone 5.
SDK_ABIS ?= x86_64 arm64-v8a

.PHONY: sdk-native
sdk-native: ## Build the SDK native libraries (JNI) for $(SDK_ABIS)
	@mkdir -p $(OUT)
	$(SDK_RUN) bash -c 'cd sdk && ./build.sh -p groundsdk-android --no-color -j \
		--abis $(SDK_ABIS) -t build-jni' 2>&1 | tee $(OUT)/sdk-native.log; \
		exit $${PIPESTATUS[0]}

SDK_GRADLE_DIR := /work/sdk/products/groundsdk/android/gradle
SDK_DEMO_APK   := sdk/out/groundsdk-android/gradle/groundsdkdemo/outputs/apk/debug/groundsdkdemo-debug.apk

.PHONY: sdk-demo
sdk-demo: ## Build the SDK demo app debug APK into out/groundsdkdemo-debug.apk
	@mkdir -p $(OUT)
	docker run --rm $(SDK_VOLUMES) -w $(SDK_GRADLE_DIR) $(SDK_IMAGE) \
		./gradlew --no-daemon --console=plain :groundsdkdemo:assembleDebug 2>&1 \
		| tee $(OUT)/sdk-demo.log; exit $${PIPESTATUS[0]}
	cp $(SDK_DEMO_APK) $(OUT)/groundsdkdemo-debug.apk
	$(SDK_RUN) aapt dump badging /work/out/groundsdkdemo-debug.apk | grep -E "^package|native-code"

.PHONY: sdk
sdk: sdk-sync sdk-native sdk-demo ## Sync and build the GroundSdk fork (native libs, demo APK)

.PHONY: app
app: ## Build the app debug APK
	$(call not_implemented,M3)

.PHONY: unit
unit: ## Run JVM unit tests
	$(call not_implemented,M3)

# Fake controller: built for Linux x86_64 with the SDK's olympe-linux product.
# Our own modules (fake-sc2/) are added to the Alchemy scan, and SDK packages
# the fake doesn't need (video, ffmpeg, python, Android) are pruned, so no
# SDK file is modified.
FAKE_PRUNE := $(addprefix /work/sdk/packages/, pdraw olympe groundsdk-android \
	common/ffmpeg common/eigen common/python-native common/olympe-deps \
	common/ctypeslib common/libmp4 common/libh264 common/libvideo-buffers \
	common/libvideo-decode common/libvideo-metadata common/libvideo-streaming \
	common/libmediacodec-wrapper common/librtsp common/libsdp common/librtp \
	common/libARMavlink common/mavlink common/lz4 \
	common/logger)
FAKE_MODULES ?= arsdk-ng-device arsdk-ng-controller

.PHONY: fake-build
fake-build: ## Build the fake controller (and Parrot's C examples) for Linux x86_64
	@mkdir -p $(OUT)
	docker run --rm \
		-e ALCHEMY_TARGET_SCAN_ADD_DIRS=/work/fake-sc2 \
		-e ALCHEMY_TARGET_SCAN_PRUNE_DIRS="$(FAKE_PRUNE)" \
		$(SDK_VOLUMES) -w /work/sdk $(SDK_IMAGE) \
		./build.sh -p olympe-linux --no-color -j -A USE_CONFIG_CHECK=0 $(FAKE_MODULES) 2>&1 \
		| tee $(OUT)/fake-build.log; exit $${PIPESTATUS[0]}

.PHONY: fake
fake: ## Build and start the fake SkyController 2 + Disco
	$(call not_implemented,T1.3)

EMU_IMAGE := disco-fpv/emulator:1
EMU_NAME  := disco-fpv-emu
ADB       := docker exec $(EMU_NAME) adb -s emulator-5554

.PHONY: emu-image
emu-image: ## Build the Docker image of the headless Android emulator
	docker build --build-arg UID=$(UID) --build-arg GID=$(GID) \
		-t $(EMU_IMAGE) docker/emulator

.PHONY: emu
emu: emu-image ## Start a headless Android emulator and wait for boot
	scripts/emu-start.sh $(EMU_IMAGE) $(EMU_NAME) $(OUT)

.PHONY: emu-stop
emu-stop: ## Stop the emulator container
	docker rm -f $(EMU_NAME) >/dev/null 2>&1 || true

.PHONY: emu-screenshot
emu-screenshot: ## Save an emulator screenshot to out/screenshot-<time>.png
	@f=$(OUT)/screenshot-$$(date +%H%M%S).png; \
		$(ADB) exec-out screencap -p > $$f && echo $$f

.PHONY: demo-run
demo-run: ## Install and launch the SDK demo app on the emulator, then screenshot
	docker cp $(OUT)/groundsdkdemo-debug.apk $(EMU_NAME):/tmp/demo.apk
	$(ADB) install -r -g /tmp/demo.apk
	$(ADB) shell am start -W -n com.parrot.drone.groundsdkdemo/.DeviceListActivity
	@sleep 5
	@$(MAKE) --no-print-directory emu-screenshot

.PHONY: e2e
e2e: ## Run end-to-end scenarios against emulator + fake
	$(call not_implemented,T1.6)

.PHONY: check
check: app unit fake emu e2e ## Full verification; exit code is the verdict

.PHONY: clean
clean: ## Remove build outputs and test artifacts (keeps sdk/ and Docker caches)
	rm -rf $(OUT)
