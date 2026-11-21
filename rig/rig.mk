# The test rig (rig/): fake SkyController 2 + Disco, fake camera, Android
# emulator with the emulated USB accessory, simulated Disco (ArduPilot SITL,
# FlightGear or Gazebo), FreeFlight Pro against the fakes. Included by the
# top Makefile, run from the repository root: paths are relative to it, and
# it uses the SDK and app build images and the e2e variables defined there.

# Fake controller: built for Linux x86_64 with the SDK's olympe-linux product.
# Our own modules (rig/fake-sc2/) are added to the Alchemy scan, and SDK packages
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
# The Disco's FTP servers (rig/disco-ftp): media, flight data, flight plans,
# firmware, on the "drone" address fake-sc2 gives GroundSdk; files shared
# with the fake in FAKE_FS.
FTP_NAME     := disco-fpv-ftp
FAKE_FS      := $(OUT)/disco-fs
DRONE_ADDR   ?= 127.0.0.2
FAKE_BIN_DIR := /work/sdk/out/olympe-linux/staging/usr/bin
FAKE_LIB_DIR := /work/sdk/out/olympe-linux/staging/usr/lib
FAKE_PORT    ?= 4321
FAKE_ARGS    ?=

.PHONY: fake-build
fake-build: ## Build the fake controller (and Parrot's C examples) for Linux x86_64
	@mkdir -p $(OUT)
	docker run --rm \
		-e ALCHEMY_TARGET_SCAN_ADD_DIRS=/work/rig/fake-sc2 \
		-e ALCHEMY_TARGET_SCAN_PRUNE_DIRS="$(FAKE_PRUNE)" \
		$(SDK_VOLUMES) -w /work/sdk $(SDK_IMAGE) \
		./build.sh -p olympe-linux --no-color -j -A USE_CONFIG_CHECK=0 $(FAKE_MODULES) 2>&1 \
		| tee $(OUT)/fake-build.log; exit $${PIPESTATUS[0]}

.PHONY: fake
fake: fake-build ## Build and start the fake SkyController 2 + Disco (container disco-fpv-fake)
	docker rm -f $(FAKE_NAME) >/dev/null 2>&1 || true
	docker rm -f $(FTP_NAME) >/dev/null 2>&1 || true
	@mkdir -p $(FAKE_FS)
	# as root, to bind the Disco's port 21; files handed to the rig user
	docker run -d --name $(FTP_NAME) --network host \
		-v "$(ROOT)/rig/disco-ftp":/f:ro -v "$(FAKE_FS)":/fs -w /f $(NODE_IMAGE) \
		node src/main.ts --root /fs --host $(DRONE_ADDR) >/dev/null
	docker run -d --name $(FAKE_NAME) --network host \
		-v "$(ROOT)":/work -e LD_LIBRARY_PATH=$(FAKE_LIB_DIR) \
		-e ULOG_LEVEL=D -e ULOG_STDERR=y $(FAKE_ENV) $(SDK_IMAGE) \
		$(FAKE_BIN_DIR)/fake-sc2 --mux --mux-port $(FAKE_PORT) \
		--drone-addr $(DRONE_ADDR) --fs /work/out/disco-fs \
		--no-publisher-avahi --no-publisher-net $(FAKE_ARGS) >/dev/null
	@sleep 1; docker ps --format '{{.Names}}' | grep -qx $(FAKE_NAME) \
		|| { docker logs $(FAKE_NAME); exit 1; }
	@echo "fake-sc2 listening on mux/TCP port $(FAKE_PORT)"

.PHONY: fake-stop
fake-stop: ## Stop the fake controller and save its log to out/fake-sc2.log
	@mkdir -p $(OUT)
	-docker logs $(FAKE_NAME) > $(OUT)/fake-sc2.log 2>&1
	-docker logs $(FTP_NAME) > $(OUT)/disco-ftp.log 2>&1
	docker rm -f $(FAKE_NAME) $(FTP_NAME) >/dev/null 2>&1 || true

# Fake Disco camera (GStreamer): RTP H.264 to fake-sc2 on 127.0.0.1:55004/55005.
VIDEO_IMAGE := disco-fpv/video-src:1
VIDEO_NAME  := disco-fpv-video
VIDEO_ENV   ?=

.PHONY: fake-test
fake-test: fake-build ## Protocol tests of the fake Disco (rig/tests/fake/*.probe), no emulator needed
	chmod +x rig/scripts/fake-test.sh
	rig/scripts/fake-test.sh $(SDK_IMAGE) $(OUT) $(PROBES)

.PHONY: video-image
video-image: ## Build the Docker image of the fake camera (GStreamer test pattern)
	docker build -t $(VIDEO_IMAGE) rig/docker/video-src

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
		-t $(EMU_IMAGE) rig/docker/emulator

.PHONY: emu
emu: emu-image ## Start a headless Android emulator and wait for boot
	rig/scripts/emu-start.sh $(EMU_IMAGE) $(EMU_NAME) $(OUT)

.PHONY: emu-stop
emu-stop: ## Stop the emulator container
	docker rm -f $(EMU_NAME) >/dev/null 2>&1 || true

.PHONY: emu-screenshot
emu-screenshot: ## Save an emulator screenshot to out/screenshot-<time>.png
	@f=$(OUT)/screenshot-$$(date +%H%M%S).png; \
		$(ADB) exec-out screencap -p > $$f && echo $$f

# Real USB mode: the app reaches the fake SkyController 2 through Android's
# USB accessory path, inside the emulator (rig/scripts/emu-usb-setup.sh): the
# emulated phone is put in accessory mode on its dummy USB controller and
# aoa-host plays the controller's USB side, relaying to fake-sc2.
NDK_CC = $(SDK_NDK)/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android24-clang

.PHONY: aoa-host
aoa-host: ## Build the emulated SkyController's USB side (out/aoa-host, runs in the emulator)
	@mkdir -p $(OUT)
	docker run --rm -u $(UID):$(GID) -v "$(ROOT)":/work -w /work $(SDK_IMAGE) \
		$(NDK_CC) -O2 -Wall -Wextra -Wl,-z,max-page-size=16384 -o out/aoa-host rig/aoa-host/aoa_host.c

.PHONY: aoa-host-linux
aoa-host-linux: ## Build aoa-host for this PC (out/aoa-host-linux): a physical phone on USB talks to the fakes (QUICKSTART.md)
	@mkdir -p $(OUT)
	docker run --rm -u $(UID):$(GID) -v "$(ROOT)":/work -w /work $(SDK_IMAGE) \
		gcc -O2 -Wall -Wextra -static -pthread -o out/aoa-host-linux rig/aoa-host/aoa_host.c

.PHONY: emu-usb
emu-usb: emu-image aoa-host ## Start the emulator in real USB mode (writable system, accessory feature, aoa-host)
	EMU_MODE_ARGS=-writable-system rig/scripts/emu-start.sh $(EMU_IMAGE) $(EMU_NAME) $(OUT)
	rig/scripts/emu-usb-setup.sh $(EMU_NAME) $(OUT)/aoa-host $(FAKE_PORT)

# Simulated Disco (rig/sim/): ArduPilot SITL (ArduPlane, flying wing) for the
# flight, FlightGear following it for the camera (streamed to fake-sc2's
# video input instead of the test pattern), and a TypeScript bridge feeding
# fake-sc2 from SITL's MAVLink and turning the app's piloting commands into
# autopilot modes. Replaces `make video`; needs `make fake`.
SITL_IMAGE := disco-fpv/sitl:1
FG_IMAGE   := disco-fpv/flightgear:1
GZ_IMAGE   := disco-fpv/gazebo:1
# flightgear: SITL's flying-wing model, FlightGear's scenery for the camera;
# gazebo: Gazebo's physics (ArduPilot's Zephyr) and 3D world (rig/sim/gazebo)
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
	docker build --build-context loopback=rig/docker/loopback-bind -t $(SITL_IMAGE) rig/docker/sitl
	docker build --build-context loopback=rig/docker/loopback-bind -t $(FG_IMAGE) rig/docker/flightgear
	docker build --build-context loopback=rig/docker/loopback-bind -t $(GZ_IMAGE) rig/docker/gazebo

.PHONY: sim-world
sim-world: rig/sim/bridge/node_modules ## Regenerate the Gazebo world (rig/sim/gazebo/worlds, models/fuel_* and disco_ground)
	docker run --rm -u $(UID):$(GID) -v "$(ROOT)/rig/sim":/s -w /s/bridge $(NODE_IMAGE) \
		node src/gazebo-world.ts ../gazebo

rig/sim/bridge/node_modules: rig/sim/bridge/package.json
	docker run --rm -u $(UID):$(GID) -e npm_config_cache=/tmp/npm -v "$(ROOT)/rig/sim/bridge":/b -w /b \
		$(NODE_IMAGE) npm install --ignore-scripts --no-audit --no-fund
	@touch $@

.PHONY: sim-unit
sim-unit: rig/sim/bridge/node_modules ## Type-check and unit-test the simulator bridge (in Docker)
	docker run --rm -u $(UID):$(GID) -v "$(ROOT)/rig/sim/bridge":/b -w /b $(NODE_IMAGE) \
		sh -c 'node_modules/.bin/tsc --noEmit -p . && node --test test/*.test.ts'

rig/disco-ftp/node_modules: rig/disco-ftp/package.json
	docker run --rm -u $(UID):$(GID) -e npm_config_cache=/tmp/npm -v "$(ROOT)/rig/disco-ftp":/b -w /b \
		$(NODE_IMAGE) npm install --ignore-scripts --no-audit --no-fund
	@touch $@

.PHONY: ftp-unit
ftp-unit: rig/disco-ftp/node_modules ## Type-check and unit-test the fake Disco's FTP server (in Docker)
	docker run --rm -u $(UID):$(GID) -v "$(ROOT)/rig/disco-ftp":/b -w /b $(NODE_IMAGE) \
		sh -c 'node_modules/.bin/tsc --noEmit -p . && node --test test/*.test.ts'

.PHONY: sim
sim: ## Fly the simulated Disco: SITL, camera (SIM_ENGINE=flightgear|gazebo), bridge to fake-sc2 (needs make fake)
	docker rm -f $(VIDEO_NAME) $(SIM_NAMES) >/dev/null 2>&1 || true
ifeq ($(SIM_ENGINE),gazebo)
	docker run -d --name disco-fpv-gz --network host $(SIM_GPU) -e GZ_IP=127.0.0.1 \
		-v "$(ROOT)/rig/sim":/sim:ro $(GZ_IMAGE) >/dev/null
else
	docker run -d --name disco-fpv-fg --network host $(SIM_GPU) -e HOME_LOC=$(SIM_HOME) $(FG_IMAGE) >/dev/null
endif
	docker run -d --name disco-fpv-sitl --network host -e HOME_LOC=$(SIM_HOME) $(SIM_SITL_ENV) \
		-v "$(ROOT)/rig/sim":/sim:ro $(SITL_IMAGE) >/dev/null
	docker run -d --name disco-fpv-bridge --network host -u $(UID):$(GID) \
		-v "$(ROOT)/rig/sim/bridge":/b -w /b $(NODE_IMAGE) node src/main.ts $(SIM_ARGS) >/dev/null
	@echo "simulated Disco started (logs: docker logs disco-fpv-bridge, -sitl, -fg)"

.PHONY: sim-view
sim-view: ## Line-of-sight view of the Gazebo Disco on the PC's screen (SIM_ENGINE=gazebo)
	docker rm -f disco-fpv-gz-view >/dev/null 2>&1 || true
	docker run -d --name disco-fpv-gz-view --network host $(SIM_GPU) -u $(UID):$(GID) \
		-e GZ_IP=127.0.0.1 -e DISPLAY=$(SIM_DISPLAY) -e XAUTHORITY=/tmp/.Xauthority -e HOME=/tmp \
		-v /tmp/.X11-unix:/tmp/.X11-unix:ro -v "$(SIM_XAUTH)":/tmp/.Xauthority:ro \
		-v "$(ROOT)/rig/sim":/sim:ro $(GZ_IMAGE) sim-view >/dev/null
	@echo "line-of-sight view started (logs: docker logs disco-fpv-gz-view)"

.PHONY: sim-crash
sim-crash: ## Crash the simulated Disco nose first (test tool: MANUAL, nose down, on SITL's 2nd port); needs make sim
	docker run --rm --network host -u $(UID):$(GID) -v "$(ROOT)/rig/sim/bridge":/b -w /b $(NODE_IMAGE) \
		node src/crash.ts $(CRASH_ARGS)

.PHONY: sim-stop
sim-stop: ## Stop the simulated Disco, logs to out/sim-*.log
	@mkdir -p $(OUT)
	-for c in $(SIM_NAMES); do docker logs $$c > $(OUT)/sim-$${c#disco-fpv-}.log 2>&1; done
	docker rm -f $(SIM_NAMES) >/dev/null 2>&1 || true
	@# the bridge switched the fake's own flight model off, and a crash
	@# cut the motor or the link: all back to normal
	-printf 'sim internal\nfail cut_out off\ndrone_link ok\n' | nc -q1 127.0.0.1 $(E2E_CTRL_PORT) >/dev/null 2>&1

# FreeFlight Pro validation (TASKS.md M1b): the fake Disco built for Android
# runs inside the emulator in Wi-Fi mode (--disco), announced over mDNS by a
# helper app, with the fake camera on the host. FreeFlight itself is not
# redistributed: FREEFLIGHT_APK points at your own copy.
FREEFLIGHT_APK ?=

.PHONY: fake-android
fake-android: ## Build fake-sc2 for the x86_64 emulator (Android NDK)
	@mkdir -p $(OUT)
	docker run --rm -e ALCHEMY_TARGET_SCAN_ADD_DIRS=/work/rig/fake-sc2 \
		$(SDK_VOLUMES) -w /work/sdk $(SDK_IMAGE) \
		./build.sh -p groundsdk-android --no-color -j --abis x86_64 \
		-A USE_CONFIG_CHECK=0 fake-sc2 2>&1 \
		| tee $(OUT)/fake-android.log; exit $${PIPESTATUS[0]}
	$(SDK_RUN) cp $(SDK_NDK)/sources/cxx-stl/llvm-libc++/libs/x86_64/libc++_shared.so /work/out/

.PHONY: freeflight-helper
freeflight-helper: app-image ## Build the mDNS helper app (out/fakedisco-debug.apk)
	@mkdir -p $(OUT)
	docker run --rm -v "$(ROOT)":/work -v disco-fpv-app-gradle:/home/builder/.gradle \
		-v disco-fpv-android:/home/builder/.android -w /work/rig/fakedisco $(APP_IMAGE) \
		gradle --no-daemon --console=plain assembleDebug 2>&1 \
		| tee $(OUT)/fakedisco-build.log; exit $${PIPESTATUS[0]}
	cp rig/fakedisco/build/outputs/apk/debug/fakedisco-debug.apk $(OUT)/

.PHONY: freeflight
freeflight: fake-android freeflight-helper ## Run FreeFlight against the fake Disco (FREEFLIGHT_APK=...); needs make emu
	$(MAKE) --no-print-directory video VIDEO_ENV="-e RTP_PORT=56004 -e RTCP_PORT=56005"
	rig/scripts/freeflight-setup.sh $(EMU_NAME) $(OUT) $(FREEFLIGHT_APK)

.PHONY: wifi-disco
wifi-disco: fake-android freeflight-helper ## The fake Disco on the emulator's Wi-Fi (mDNS), for our app without a controller; needs make emu
	$(MAKE) --no-print-directory fake-stop
	$(MAKE) --no-print-directory video VIDEO_ENV="-e RTP_PORT=56004 -e RTCP_PORT=56005"
	rig/scripts/freeflight-setup.sh $(EMU_NAME) $(OUT) none

.PHONY: freeflight-stop
freeflight-stop: ## Stop FreeFlight, the in-emulator fake Disco and its mDNS announce
	-$(ADB) shell am force-stop com.parrot.freeflight3
	-$(ADB) shell am force-stop io.github.bitone.discofpv.fakedisco
	-$(ADB) shell pkill fake-sc2
	-$(ADB) shell cat /data/local/tmp/fake.log > $(OUT)/fake-disco-android.log
