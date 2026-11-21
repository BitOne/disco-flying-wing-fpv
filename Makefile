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
help: ## List targets (app and SDK here, the test rig in rig/rig.mk)
	@for f in $(MAKEFILE_LIST); do echo "$$f:"; grep -E '^[a-zA-Z0-9_-]+:.*?## ' $$f \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  %-18s %s\n", $$1, $$2}'; done

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

# The NDK in the SDK image (aoa-host, apk-check).
SDK_NDK := /opt/android-sdk/ndk/21.4.7075529

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
app: app-image ## Build the app debug APK (out/disco-fpv-debug.apk); DEBUG_BRIDGE=<PC address> for a phone; needs sdk-aar
	@test -f $(SDK_AAR_DIR)/sdkcore.aar || { echo "run make sdk-aar first" >&2; exit 1; }
	@mkdir -p $(OUT)
	$(APP_RUN) gradle --no-daemon --console=plain :app:assembleDebug \
		$(if $(DEBUG_BRIDGE),-PdebugBridgeAddress=$(DEBUG_BRIDGE)) 2>&1 \
		| tee $(OUT)/app-build.log; exit $${PIPESTATUS[0]}
	cp $(APP_DEBUG_APK) $(OUT)/disco-fpv-debug.apk

# Release (T7.3): signed with a key kept outside the repository, in
# RELEASE_DIR (back it up: updates must be signed with the same key).
RELEASE_DIR ?= $(HOME)/.config/disco-fpv-release
APP_VERSION = $(shell sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/app/build.gradle.kts)

.PHONY: release-key
release-key: app-image ## Create the release signing key in RELEASE_DIR (once; keep a backup)
	scripts/release-key.sh $(APP_IMAGE) $(RELEASE_DIR)

.PHONY: release
release: app-image ## Build the signed release APK (out/disco-fpv-<version>.apk); needs sdk-aar and release-key
	@test -f $(RELEASE_DIR)/release.properties || { echo "no release key: make release-key"; exit 1; }
	docker run --rm -v "$(ROOT)":/work -v disco-fpv-app-gradle:/home/builder/.gradle \
		-v $(RELEASE_DIR):/signing:ro -w /work/app $(APP_IMAGE) \
		gradle --no-daemon --console=plain :app:assembleRelease -PreleaseSigning=/signing/release.properties 2>&1 \
		| tee $(OUT)/release-build.log; test $${PIPESTATUS[0]} = 0
	cp app/app/build/outputs/apk/release/app-release.apk $(OUT)/disco-fpv-$(APP_VERSION).apk
	$(APP_RUN) /opt/android-sdk/build-tools/35.0.0/apksigner verify --print-certs /work/out/disco-fpv-$(APP_VERSION).apk
	@echo "$(OUT)/disco-fpv-$(APP_VERSION).apk"

.PHONY: app-usb
app-usb: app-image ## Build the app without the debug bridge (out/disco-fpv-usb.apk): USB accessory only
	@mkdir -p $(OUT)
	$(APP_RUN) gradle --no-daemon --console=plain :app:assembleUsb 2>&1 \
		| tee $(OUT)/app-usb-build.log; exit $${PIPESTATUS[0]}
	cp app/app/build/outputs/apk/usb/app-usb.apk $(OUT)/disco-fpv-usb.apk

.PHONY: app-usb-install
app-usb-install: ## Install out/disco-fpv-usb.apk on the emulator
	docker cp $(OUT)/disco-fpv-usb.apk $(EMU_NAME):/tmp/disco-fpv.apk
	docker exec $(EMU_NAME) adb -s emulator-5554 install -r -g /tmp/disco-fpv.apk \
		|| { docker exec $(EMU_NAME) adb -s emulator-5554 uninstall io.github.bitone.discofpv; \
		docker exec $(EMU_NAME) adb -s emulator-5554 install -r -g /tmp/disco-fpv.apk; }
	@# a copy on the device, for scenarios reinstalling it (usb-permission.yaml)
	docker exec $(EMU_NAME) adb -s emulator-5554 push /tmp/disco-fpv.apk /data/local/tmp/disco-fpv.apk >/dev/null

.PHONY: check-usb
check-usb: app-usb fake video ## make check through the emulated USB accessory instead of the debug bridge
	$(MAKE) --no-print-directory emu-stop
	$(MAKE) --no-print-directory emu-image aoa-host
	EMU_MODE_ARGS=-writable-system rig/scripts/emu-start.sh $(EMU_IMAGE) $(EMU_NAME) $(OUT)
	$(MAKE) --no-print-directory app-usb-install
	rig/scripts/emu-usb-setup.sh $(EMU_NAME) $(OUT)/aoa-host $(FAKE_PORT)
	$(MAKE) --no-print-directory e2e SCENARIOS="$(SCENARIOS) tests/scenarios/usb-permission.yaml"

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

# --- The test rig (rig/rig.mk) ----------------------------------------------

include rig/rig.mk

.PHONY: check
check: app apk-check unit fake-test ftp-unit firmware-sample fake video emu app-install e2e ## Full verification; exit code is the verdict

.PHONY: stop
stop: sim-stop video-stop fake-stop emu-stop ## Stop everything running: simulator, camera, fake, emulator (logs to out/)

.PHONY: clean
clean: ## Remove build outputs and test artifacts (keeps sdk/ and Docker caches)
	rm -rf $(OUT)
