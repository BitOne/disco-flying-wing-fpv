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
SDK_RUN = docker run --rm \
	-v "$(ROOT)":/work \
	-v disco-fpv-cache:/home/builder/.cache \
	-v disco-fpv-gradle:/home/builder/.gradle \
	-w /work $(SDK_IMAGE)

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
	docker run --rm -it \
		-v "$(ROOT)":/work \
		-v disco-fpv-cache:/home/builder/.cache \
		-v disco-fpv-gradle:/home/builder/.gradle \
		-w /work $(SDK_IMAGE) bash

# --- Main entry points (PLAN.md section 3) --------------------------------

.PHONY: sdk-sync
sdk-sync: ## Check out the GroundSdk fork into sdk/ (pinned by sdk.lock.xml)
	$(SDK_RUN) scripts/sdk-sync.sh

.PHONY: sdk
sdk: sdk-sync ## Sync and build the GroundSdk fork (native libs + AARs)
	$(call not_implemented,T0.4 and T0.5)

.PHONY: app
app: ## Build the app debug APK
	$(call not_implemented,M3)

.PHONY: unit
unit: ## Run JVM unit tests
	$(call not_implemented,M3)

.PHONY: fake
fake: ## Build and start the fake SkyController 2 + Disco
	$(call not_implemented,M1)

.PHONY: emu
emu: ## Start a headless Android emulator and wait for boot
	$(call not_implemented,T0.6)

.PHONY: e2e
e2e: ## Run end-to-end scenarios against emulator + fake
	$(call not_implemented,T1.6)

.PHONY: check
check: app unit fake emu e2e ## Full verification; exit code is the verdict

.PHONY: clean
clean: ## Remove build outputs and test artifacts (keeps sdk/ and Docker caches)
	rm -rf $(OUT)
