# disco-flying-wing-fpv: single entry points for building and testing.
# See PLAN.md section 3. Targets not implemented yet fail with the task id
# that will implement them, so `make check` can never pass by accident.

SHELL := /bin/bash
.DEFAULT_GOAL := help

ROOT        := $(abspath .)
OUT         := $(ROOT)/out
# Evaluated once (:=), so every use in a run sees the same timestamp.
RUN_ID      ?= $(shell date +%Y%m%d-%H%M%S)
RUN_ID      := $(RUN_ID)
RUN_DIR     := $(OUT)/$(RUN_ID)

SDK_IMAGE   := disco-fpv/sdk-build:1
UID         := $(shell id -u)
GID         := $(shell id -g)

# Run a command inside the SDK build container, with the project mounted at /work
# and a persistent cache volume for repo, Gradle and ccache data.
# The dragon_build volume keeps the Parrot EULA acceptance marker (make sdk-eula);
# the android volume keeps the debug signing key, so reinstalls don't fail on
# a signature mismatch.
SDK_VOLUMES = \
	-v "$(ROOT)":/work \
	-v disco-fpv-cache:/home/builder/.cache \
	-v disco-fpv-gradle:/home/builder/.gradle \
	-v disco-fpv-dragon:/home/builder/.config/dragon_build \
	-v disco-fpv-android:/home/builder/.android
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

# Demo app variant for end-to-end tests: debug USB bridge pointed at the fake
# controller (10.0.2.2:$(FAKE_PORT)) and auto-connection on, through a Gradle
# init script (no SDK edit).
.PHONY: sdk-demo-e2e
sdk-demo-e2e: ## Build the demo APK wired to the fake controller (out/groundsdkdemo-e2e.apk)
	@mkdir -p $(OUT)
	docker run --rm $(SDK_VOLUMES) -w $(SDK_GRADLE_DIR) $(SDK_IMAGE) \
		./gradlew --no-daemon --console=plain \
		-I /work/scripts/gradle/debug-bridge.init.gradle -PdebugBridgePort=$(FAKE_PORT) \
		:groundsdkdemo:assembleDebug 2>&1 | tee $(OUT)/sdk-demo-e2e.log; exit $${PIPESTATUS[0]}
	cp $(SDK_DEMO_APK) $(OUT)/groundsdkdemo-e2e.apk

# SDK modules as release AARs, consumed by our app (app/), which is built with
# a modern toolchain in its own image. sdkcore carries libsdkcore.so.
SDK_AAR_MODULES := sdkcore groundsdk arsdkengine sdkcore-testing
SDK_AAR_DIR     := $(OUT)/sdk-aar

.PHONY: sdk-aar
sdk-aar: ## Build the SDK modules as AARs into out/sdk-aar/ (needs sdk-native)
	@mkdir -p $(SDK_AAR_DIR)
	docker run --rm $(SDK_VOLUMES) -w $(SDK_GRADLE_DIR) $(SDK_IMAGE) \
		./gradlew --no-daemon --console=plain \
		$(foreach m,$(SDK_AAR_MODULES),:$(m):assembleRelease) 2>&1 \
		| tee $(OUT)/sdk-aar.log; exit $${PIPESTATUS[0]}
	$(foreach m,$(SDK_AAR_MODULES),cp sdk/out/groundsdk-android/gradle/$(m)/outputs/aar/$(m)-release.aar $(SDK_AAR_DIR)/$(m).aar &&) true
	@ls $(SDK_AAR_DIR)

.PHONY: sdk
sdk: sdk-sync sdk-native sdk-demo ## Sync and build the GroundSdk fork (native libs, demo APK)

# Our app (app/): modern toolchain image, SDK consumed from out/sdk-aar.
APP_IMAGE := disco-fpv/app-build:1
APP_RUN = docker run --rm \
	-v "$(ROOT)":/work \
	-v disco-fpv-app-gradle:/home/builder/.gradle \
	-v disco-fpv-android:/home/builder/.android \
	-w /work/app $(APP_IMAGE)
APP_DEBUG_APK := app/app/build/outputs/apk/debug/app-debug.apk

.PHONY: app-image
app-image: ## Build the Docker image used to build the app
	docker build --build-arg UID=$(UID) --build-arg GID=$(GID) -t $(APP_IMAGE) docker/app-build

.PHONY: app
app: app-image ## Build the app debug APK (out/disco-fpv-debug.apk); needs sdk-aar
	@test -f $(SDK_AAR_DIR)/sdkcore.aar || { echo "run make sdk-aar first" >&2; exit 1; }
	@mkdir -p $(OUT)
	$(APP_RUN) gradle --no-daemon --console=plain :app:assembleDebug 2>&1 \
		| tee $(OUT)/app-build.log; exit $${PIPESTATUS[0]}
	cp $(APP_DEBUG_APK) $(OUT)/disco-fpv-debug.apk

.PHONY: app-usb
app-usb: app-image ## Build the app without the debug bridge (out/disco-fpv-usb.apk): USB accessory only
	@mkdir -p $(OUT)
	$(APP_RUN) gradle --no-daemon --console=plain :app:assembleUsb 2>&1 \
		| tee $(OUT)/app-usb-build.log; exit $${PIPESTATUS[0]}
	cp app/app/build/outputs/apk/usb/app-usb.apk $(OUT)/disco-fpv-usb.apk

# Real USB mode: the app reaches the fake SkyController 2 through Android's
# USB accessory path, inside the emulator (scripts/emu-usb-setup.sh): the
# emulated phone is put in accessory mode on its dummy USB controller and
# aoa-host plays the controller's USB side, relaying to fake-sc2.
NDK_CC = $(SDK_NDK)/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android24-clang

.PHONY: aoa-host
aoa-host: ## Build the emulated SkyController's USB side (out/aoa-host, runs in the emulator)
	@mkdir -p $(OUT)
	docker run --rm -u $(UID):$(GID) -v "$(ROOT)":/work -w /work $(SDK_IMAGE) \
		$(NDK_CC) -O2 -Wall -Wextra -Wl,-z,max-page-size=16384 -o out/aoa-host aoa-host/aoa_host.c

.PHONY: emu-usb
emu-usb: emu-image aoa-host ## Start the emulator in real USB mode (writable system, accessory feature, aoa-host)
	EMU_MODE_ARGS=-writable-system scripts/emu-start.sh $(EMU_IMAGE) $(EMU_NAME) $(OUT)
	scripts/emu-usb-setup.sh $(EMU_NAME) $(OUT)/aoa-host $(FAKE_PORT)

.PHONY: app-usb-install
app-usb-install: ## Install out/disco-fpv-usb.apk on the emulator
	docker cp $(OUT)/disco-fpv-usb.apk $(EMU_NAME):/tmp/disco-fpv.apk
	docker exec $(EMU_NAME) adb -s emulator-5554 install -r -g /tmp/disco-fpv.apk \
		|| { docker exec $(EMU_NAME) adb -s emulator-5554 uninstall io.github.bitone.discofpv; \
		docker exec $(EMU_NAME) adb -s emulator-5554 install -r -g /tmp/disco-fpv.apk; }

.PHONY: check-usb
check-usb: app-usb fake video ## make check through the emulated USB accessory instead of the debug bridge
	$(MAKE) --no-print-directory emu-stop
	$(MAKE) --no-print-directory emu-image aoa-host
	EMU_MODE_ARGS=-writable-system scripts/emu-start.sh $(EMU_IMAGE) $(EMU_NAME) $(OUT)
	$(MAKE) --no-print-directory app-usb-install
	scripts/emu-usb-setup.sh $(EMU_NAME) $(OUT)/aoa-host $(FAKE_PORT)
	$(MAKE) --no-print-directory e2e

# Simulated Disco (sim/): ArduPilot SITL (ArduPlane, flying wing) for the
# flight, FlightGear following it for the camera (streamed to fake-sc2's
# video input instead of the test pattern), and a TypeScript bridge feeding
# fake-sc2 from SITL's MAVLink and turning the app's piloting commands into
# autopilot modes. Replaces `make video`; needs `make fake`.
SITL_IMAGE := disco-fpv/sitl:1
FG_IMAGE   := disco-fpv/flightgear:1
GZ_IMAGE   := disco-fpv/gazebo:1
# flightgear: SITL's flying-wing model, FlightGear's scenery for the camera;
# gazebo: Gazebo's physics (ArduPilot's Zephyr) and 3D world (sim/gazebo)
SIM_ENGINE ?= flightgear
# lat,lon,alt,heading: Kapiolani Park, Honolulu (Waikiki, Diamond Head),
# in the Hawaii scenery bundled with FlightGear
SIM_HOME   ?= 21.2665,-157.8205,3,60
SIM_ARGS   ?= --auto-takeoff
SIM_NAMES  := disco-fpv-sitl disco-fpv-fg disco-fpv-gz disco-fpv-gz-view disco-fpv-bridge
SIM_SITL_ENV = $(if $(filter gazebo,$(SIM_ENGINE)),-e SITL_MODEL=JSON:127.0.0.1 \
	-e SITL_DEFAULTS=Tools/autotest/default_params/gazebo-zephyr.parm)
# the PC's screen, for the line-of-sight view (make sim-view)
SIM_DISPLAY ?= $(or $(DISPLAY),:0)
SIM_XAUTH   ?= $(or $(XAUTHORITY),$(HOME)/.Xauthority)
# The host GPU for FlightGear (VirtualGL's EGL back end wants the card
# device): software rendering is far too slow. SIM_CARD=none to go without.
SIM_CARD   ?= $(firstword $(wildcard /dev/dri/card*))
SIM_RENDER ?= $(firstword $(wildcard /dev/dri/renderD*))
SIM_GPU     = $(if $(filter-out none,$(SIM_CARD)),--device $(SIM_CARD) --device $(SIM_RENDER) \
	--group-add $(shell stat -c %g $(SIM_CARD)) --group-add $(shell stat -c %g $(SIM_RENDER)) \
	-e GPU_DEVICE=$(SIM_CARD))

.PHONY: sim-image
sim-image: ## Build the simulator images (ArduPilot SITL, FlightGear, Gazebo)
	docker build -t $(SITL_IMAGE) docker/sitl
	docker build -t $(FG_IMAGE) docker/flightgear
	docker build -t $(GZ_IMAGE) docker/gazebo

.PHONY: sim-world
sim-world: sim/bridge/node_modules ## Regenerate the Gazebo world (sim/gazebo/worlds, models/fuel_* and disco_ground)
	docker run --rm -u $(UID):$(GID) -v "$(ROOT)/sim":/s -w /s/bridge $(NODE_IMAGE) \
		node src/gazebo-world.ts ../gazebo

sim/bridge/node_modules: sim/bridge/package.json
	docker run --rm -u $(UID):$(GID) -e npm_config_cache=/tmp/npm -v "$(ROOT)/sim/bridge":/b -w /b \
		$(NODE_IMAGE) npm install --ignore-scripts --no-audit --no-fund
	@touch $@

.PHONY: sim-unit
sim-unit: sim/bridge/node_modules ## Type-check and unit-test the simulator bridge (in Docker)
	docker run --rm -u $(UID):$(GID) -v "$(ROOT)/sim/bridge":/b -w /b $(NODE_IMAGE) \
		sh -c 'node_modules/.bin/tsc --noEmit -p . && node --test test/*.test.ts'

.PHONY: sim
sim: ## Fly the simulated Disco: SITL, camera (SIM_ENGINE=flightgear|gazebo), bridge to fake-sc2 (needs make fake)
	docker rm -f $(VIDEO_NAME) $(SIM_NAMES) >/dev/null 2>&1 || true
ifeq ($(SIM_ENGINE),gazebo)
	docker run -d --name disco-fpv-gz --network host $(SIM_GPU) -e GZ_IP=127.0.0.1 \
		-v "$(ROOT)/sim":/sim:ro $(GZ_IMAGE) >/dev/null
else
	docker run -d --name disco-fpv-fg --network host $(SIM_GPU) -e HOME_LOC=$(SIM_HOME) $(FG_IMAGE) >/dev/null
endif
	docker run -d --name disco-fpv-sitl --network host -e HOME_LOC=$(SIM_HOME) $(SIM_SITL_ENV) \
		-v "$(ROOT)/sim":/sim:ro $(SITL_IMAGE) >/dev/null
	docker run -d --name disco-fpv-bridge --network host -u $(UID):$(GID) \
		-v "$(ROOT)/sim/bridge":/b -w /b $(NODE_IMAGE) node src/main.ts $(SIM_ARGS) >/dev/null
	@echo "simulated Disco started (logs: docker logs disco-fpv-bridge, -sitl, -fg)"

.PHONY: sim-view
sim-view: ## Line-of-sight view of the Gazebo Disco on the PC's screen (SIM_ENGINE=gazebo)
	docker rm -f disco-fpv-gz-view >/dev/null 2>&1 || true
	docker run -d --name disco-fpv-gz-view --network host $(SIM_GPU) -u $(UID):$(GID) \
		-e GZ_IP=127.0.0.1 -e DISPLAY=$(SIM_DISPLAY) -e XAUTHORITY=/tmp/.Xauthority -e HOME=/tmp \
		-v /tmp/.X11-unix:/tmp/.X11-unix:ro -v "$(SIM_XAUTH)":/tmp/.Xauthority:ro \
		-v "$(ROOT)/sim":/sim:ro $(GZ_IMAGE) sim-view >/dev/null
	@echo "line-of-sight view started (logs: docker logs disco-fpv-gz-view)"

.PHONY: sim-stop
sim-stop: ## Stop the simulated Disco, logs to out/sim-*.log
	@mkdir -p $(OUT)
	-for c in $(SIM_NAMES); do docker logs $$c > $(OUT)/sim-$${c#disco-fpv-}.log 2>&1; done
	docker rm -f $(SIM_NAMES) >/dev/null 2>&1 || true
	@# the bridge switched the fake's own flight model off: back on
	-printf 'sim internal\n' | nc -q1 127.0.0.1 $(E2E_CTRL_PORT) >/dev/null 2>&1

.PHONY: apk-check
apk-check: ## Check out/disco-fpv-debug.apk's arm64 native libraries are 16 KB aligned
	$(SDK_RUN) scripts/apk-check.sh out/disco-fpv-debug.apk \
		$(SDK_NDK)/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf

.PHONY: app-install
app-install: ## Install out/disco-fpv-debug.apk on the emulator
	docker cp $(OUT)/disco-fpv-debug.apk $(EMU_NAME):/tmp/disco-fpv.apk
	docker exec $(EMU_NAME) adb -s emulator-5554 install -r -g /tmp/disco-fpv.apk \
		|| { docker exec $(EMU_NAME) adb -s emulator-5554 uninstall io.github.bitone.discofpv; \
		docker exec $(EMU_NAME) adb -s emulator-5554 install -r -g /tmp/disco-fpv.apk; }

.PHONY: map-test
MAP_FILE ?= $(OUT)/maps/rhone-alpes.map
MAP_DIR  := /sdcard/Android/data/io.github.bitone.discofpv/files
map-test: app-image ## Render tiles from a Mapsforge file on the emulator (MAP_FILE, default rhone-alpes.map from download.mapsforge.org)
	test -f $(MAP_FILE) || { mkdir -p $(OUT)/maps && curl -fL -o $(MAP_FILE) \
		https://download.mapsforge.org/maps/v5/europe/france/rhone-alpes.map; }
	$(APP_RUN) gradle --no-daemon --console=plain :app:assembleDebug :app:assembleDebugAndroidTest 2>&1 \
		| tee $(OUT)/map-test-build.log; test $${PIPESTATUS[0]} = 0
	docker cp $(APP_DEBUG_APK) $(EMU_NAME):/tmp/disco-fpv.apk
	docker cp app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk $(EMU_NAME):/tmp/disco-fpv-test.apk
	$(ADB) install -r -g /tmp/disco-fpv.apk && $(ADB) install -r -g /tmp/disco-fpv-test.apk
	$(ADB) shell mkdir -p $(MAP_DIR)
	$(ADB) shell test -f $(MAP_DIR)/rhone-alpes.map || { docker cp $(MAP_FILE) $(EMU_NAME):/tmp/rhone-alpes.map \
		&& $(ADB) push /tmp/rhone-alpes.map $(MAP_DIR)/; }
	$(ADB) shell am instrument -w -e class io.github.bitone.discofpv.MapsforgeTilesTest \
		io.github.bitone.discofpv.test/androidx.test.runner.AndroidJUnitRunner | tee $(OUT)/map-test.log
	grep -q "^OK (1 test)" $(OUT)/map-test.log
	mkdir -p $(OUT)/map-tiles && for f in $$($(ADB) shell ls $(MAP_DIR) | grep png); do \
		$(ADB) pull $(MAP_DIR)/$$f /tmp/$$f >/dev/null && docker cp $(EMU_NAME):/tmp/$$f $(OUT)/map-tiles/; done

.PHONY: unit
unit: app-image ## Run the app's JVM unit tests
	@mkdir -p $(OUT)
	$(APP_RUN) gradle --no-daemon --console=plain :app:testDebugUnitTest 2>&1 \
		| tee $(OUT)/app-unit.log; exit $${PIPESTATUS[0]}

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
FAKE_MODULES ?= fake-sc2 disco-probe arsdk-ng-controller
# extra docker -e options for the fake, e.g. FAKE_ENV="-e ARSDK_D2C_ACK_TIMEOUT_MS=500"
# (acknowledgement timeout towards the phone; the real SkyController 2 uses 150)
FAKE_ENV ?=
FAKE_NAME    := disco-fpv-fake
FAKE_BIN_DIR := /work/sdk/out/olympe-linux/staging/usr/bin
FAKE_LIB_DIR := /work/sdk/out/olympe-linux/staging/usr/lib
FAKE_PORT    ?= 4321
FAKE_ARGS    ?=

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
fake: fake-build ## Build and start the fake SkyController 2 + Disco (container disco-fpv-fake)
	docker rm -f $(FAKE_NAME) >/dev/null 2>&1 || true
	docker run -d --name $(FAKE_NAME) --network host \
		-v "$(ROOT)":/work -e LD_LIBRARY_PATH=$(FAKE_LIB_DIR) \
		-e ULOG_LEVEL=D -e ULOG_STDERR=y $(FAKE_ENV) $(SDK_IMAGE) \
		$(FAKE_BIN_DIR)/fake-sc2 --mux --mux-port $(FAKE_PORT) \
		--no-publisher-avahi --no-publisher-net $(FAKE_ARGS) >/dev/null
	@sleep 1; docker ps --format '{{.Names}}' | grep -qx $(FAKE_NAME) \
		|| { docker logs $(FAKE_NAME); exit 1; }
	@echo "fake-sc2 listening on mux/TCP port $(FAKE_PORT)"

.PHONY: fake-stop
fake-stop: ## Stop the fake controller and save its log to out/fake-sc2.log
	@mkdir -p $(OUT)
	-docker logs $(FAKE_NAME) > $(OUT)/fake-sc2.log 2>&1
	docker rm -f $(FAKE_NAME) >/dev/null 2>&1 || true

# Fake Disco camera (GStreamer): RTP H.264 to fake-sc2 on 127.0.0.1:55004/55005.
VIDEO_IMAGE := disco-fpv/video-src:1
VIDEO_NAME  := disco-fpv-video
VIDEO_ENV   ?=

.PHONY: fake-test
fake-test: fake-build ## Protocol tests of the fake Disco (tests/fake/*.probe), no emulator needed
	chmod +x scripts/fake-test.sh
	scripts/fake-test.sh $(SDK_IMAGE) $(OUT) $(PROBES)

.PHONY: video-image
video-image: ## Build the Docker image of the fake camera (GStreamer test pattern)
	docker build -t $(VIDEO_IMAGE) docker/video-src

.PHONY: video
video: video-image ## Start the fake camera (container disco-fpv-video); VIDEO_ENV="-e PATTERN=ball"
	docker rm -f $(VIDEO_NAME) >/dev/null 2>&1 || true
	docker run -d --name $(VIDEO_NAME) --network host $(VIDEO_ENV) $(VIDEO_IMAGE) >/dev/null
	@sleep 1; docker ps --format '{{.Names}}' | grep -qx $(VIDEO_NAME) \
		|| { docker logs $(VIDEO_NAME); exit 1; }
	@echo "fake camera streaming RTP to 127.0.0.1:55004 (RTCP 55005)"

.PHONY: video-stop
video-stop: ## Stop the fake camera
	docker rm -f $(VIDEO_NAME) >/dev/null 2>&1 || true

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

DEMO_APK ?= $(OUT)/groundsdkdemo-e2e.apk

.PHONY: demo-run
demo-run: ## Install and launch the demo app (DEMO_APK, default the e2e build), then screenshot
	docker cp $(DEMO_APK) $(EMU_NAME):/tmp/demo.apk
	$(ADB) install -r -g /tmp/demo.apk || { $(ADB) uninstall com.parrot.drone.groundsdkdemo; \
		$(ADB) install -r -g /tmp/demo.apk; }
	$(ADB) shell am start -W -n com.parrot.drone.groundsdkdemo/.DeviceListActivity
	@sleep 5
	@$(MAKE) --no-print-directory emu-screenshot

# End-to-end runner (TypeScript, tests/e2e). npm dependencies are installed and
# type-checked in a Node container (third-party code stays in Docker); the
# runner itself runs on the host (Node >= 22.18, type stripping) because it
# drives the emulator container through the Docker CLI.
NODE_IMAGE := node:22.18.0-bookworm-slim
NODE_RUN   = docker run --rm -u $(UID):$(GID) -e npm_config_cache=/tmp/npm \
	-v "$(ROOT)/tests":/tests -w /tests/e2e $(NODE_IMAGE)
# Default: our app's scenarios. The demo-*.yaml ones need the SDK demo APK
# (make sdk-demo-e2e demo-run): SCENARIOS="tests/scenarios/demo-*.yaml".
SCENARIOS  ?= tests/scenarios/app-*.yaml
E2E_CTRL_PORT ?= 4322

tests/e2e/node_modules: tests/e2e/package.json tests/e2e/package-lock.json
	$(NODE_RUN) npm ci --ignore-scripts --no-audit --no-fund
	@touch $@

.PHONY: e2e-deps
e2e-deps: tests/e2e/node_modules ## Install the e2e runner's npm dependencies (in Docker)

.PHONY: e2e-unit
e2e-unit: e2e-deps ## Type-check and unit-test the e2e runner (in Docker)
	$(NODE_RUN) sh -c 'node_modules/.bin/tsc --noEmit && npm test --silent'

# Latency, CPU and memory of the app in front (NOTES.md section 28): start
# the camera with the clock code first, `make video VIDEO_ENV="-e CLOCK_CODE=1"`
# (restart the app after: a camera restart freezes the video), open its
# video view, then `make latency LATENCY_PKG=com.parrot.freeflight3`.
LATENCY_PKG ?= io.github.bitone.discofpv
LATENCY_SAMPLES ?= 20
.PHONY: latency
latency: e2e-unit ## Video glass-to-glass latency, CPU, memory of LATENCY_PKG (clock-coded camera)
	node tests/e2e/src/latency.ts --package $(LATENCY_PKG) --samples $(LATENCY_SAMPLES) \
		--label $(LATENCY_PKG) --out $(OUT)/latency

.PHONY: e2e
e2e: e2e-unit ## Run end-to-end scenarios (SCENARIOS) against emulator + fake; out/latest
	@mkdir -p $(RUN_DIR)
	@ln -sfn $(RUN_ID) $(OUT)/latest
	node tests/e2e/src/run.ts --out $(RUN_DIR) --ctrl-port $(E2E_CTRL_PORT) $(SCENARIOS)

# FreeFlight Pro validation (TASKS.md M1b): the fake Disco built for Android
# runs inside the emulator in Wi-Fi mode (--disco), announced over mDNS by a
# helper app, with the fake camera on the host. FreeFlight itself is not
# redistributed: FREEFLIGHT_APK points at your own copy.
SDK_NDK        := /opt/android-sdk/ndk/21.4.7075529
FREEFLIGHT_APK ?=

.PHONY: fake-android
fake-android: ## Build fake-sc2 for the x86_64 emulator (Android NDK)
	@mkdir -p $(OUT)
	docker run --rm -e ALCHEMY_TARGET_SCAN_ADD_DIRS=/work/fake-sc2 \
		$(SDK_VOLUMES) -w /work/sdk $(SDK_IMAGE) \
		./build.sh -p groundsdk-android --no-color -j --abis x86_64 \
		-A USE_CONFIG_CHECK=0 fake-sc2 2>&1 \
		| tee $(OUT)/fake-android.log; exit $${PIPESTATUS[0]}
	$(SDK_RUN) cp $(SDK_NDK)/sources/cxx-stl/llvm-libc++/libs/x86_64/libc++_shared.so /work/out/

.PHONY: freeflight-helper
freeflight-helper: app-image ## Build the mDNS helper app (out/fakedisco-debug.apk)
	@mkdir -p $(OUT)
	$(APP_RUN) gradle --no-daemon --console=plain :fakedisco:assembleDebug 2>&1 \
		| tee $(OUT)/fakedisco-build.log; exit $${PIPESTATUS[0]}
	cp app/fakedisco/build/outputs/apk/debug/fakedisco-debug.apk $(OUT)/

.PHONY: freeflight
freeflight: fake-android freeflight-helper ## Run FreeFlight against the fake Disco (FREEFLIGHT_APK=...); needs make emu
	$(MAKE) --no-print-directory video VIDEO_ENV="-e RTP_PORT=56004 -e RTCP_PORT=56005"
	scripts/freeflight-setup.sh $(EMU_NAME) $(OUT) $(FREEFLIGHT_APK)

.PHONY: freeflight-stop
freeflight-stop: ## Stop FreeFlight, the in-emulator fake Disco and its mDNS announce
	-$(ADB) shell am force-stop com.parrot.freeflight3
	-$(ADB) shell am force-stop io.github.bitone.discofpv.fakedisco
	-$(ADB) shell pkill fake-sc2
	-$(ADB) shell cat /data/local/tmp/fake.log > $(OUT)/fake-disco-android.log

.PHONY: check
check: app apk-check unit fake-test fake video emu app-install e2e ## Full verification; exit code is the verdict

.PHONY: stop
stop: sim-stop video-stop fake-stop emu-stop ## Stop everything running: simulator, camera, fake, emulator (logs to out/)

.PHONY: clean
clean: ## Remove build outputs and test artifacts (keeps sdk/ and Docker caches)
	rm -rf $(OUT)
