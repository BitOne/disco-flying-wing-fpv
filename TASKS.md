# Tasks

Live backlog for the agent loop (see `PLAN.md` section 5). Work the first
open task. Each task has a done-criterion. Mark `[x]` when done, `[!]` when
blocked (with a one-line reason and date).

## M0. Toolchain (run interactively, not in the loop)

- [ ] **T0.1 Repo skeleton.** `Makefile` with stub targets (`sdk`, `app`,
  `unit`, `fake`, `emu`, `e2e`, `check`), `docker/`, `out/` ignored.
  Done: `make help` lists the targets.
- [ ] **T0.2 SDK build image.** `docker/sdk-build/Dockerfile` (Ubuntu 20.04,
  `repo`, Python 3, JDK 11, NDK `21.4.7075529`, Android SDK cmdline tools,
  platform 28). Done: image builds; `repo version` and `ndk-build --version`
  run inside.
- [ ] **T0.3 SDK checkout.** Script `scripts/sdk-sync.sh`:
  `repo init -u https://github.com/UnifiedGroundSdk/groundsdk-manifest`
  pinned to a manifest revision, `repo sync` into `sdk/` (ignored by git).
  Done: `sdk/` populated; revision recorded in `sdk.lock`.
- [ ] **T0.4 Native build.** Build the SDK native libraries for `x86_64` and
  `arm64-v8a` in the container. Done: `.so` files produced, log in `out/`.
- [ ] **T0.5 Demo APK.** Build `groundsdkdemo` debug APK. Done: APK in
  `out/`, `aapt2 dump badging` shows the package.
- [ ] **T0.6 Emulator.** Android SDK + `system-images;android-34;google_apis;x86_64`,
  headless emulator with KVM, swiftshader GPU, snapshot. `make emu` boots
  and waits for `sys.boot_completed`. Done: `adb shell getprop` works,
  screenshot saved.
- [ ] **T0.7 Demo on emulator.** Install demo APK, launch, screenshot.
  Done: screenshot shows the demo's device list.

## M1. Fake controller connects

- [ ] **T1.1 Reference binaries.** Build Parrot's `arsdk-ng` examples
  (`device`, `controller`) in Docker for byte captures and interop tests.
- [ ] **T1.2 TypeScript project.** `fake-sc2/` with Node LTS, strict
  TypeScript, test runner, lint. Done: `npm test` green on a trivial test.
- [ ] **T1.3 Mux protocol.** Port mux framing and channels from `libmux`
  (no IP proxy). Done: unit tests against captured bytes; interop with the
  C reference over TCP.
- [ ] **T1.4 ARSDK codecs.** Generate command/event encoders and decoders
  from `arsdk-xml`. Done: round-trip tests and byte equality with C encoder
  captures for a sample of commands.
- [ ] **T1.5 ARSDK transport + handshake.** Done: Parrot's C example
  controller connects to the fake and exchanges commands.
- [ ] **T1.6 SkyController 2 + Disco identity.** Device type, drone manager
  list with one Disco, connection state "connected". Done: demo app on the
  emulator (debug bridge `10.0.2.2:13579`) shows both connected.
- [ ] **T1.7 Telemetry events and scenario files.** Battery, altitude,
  attitude, speed, airspeed, GPS from a YAML timeline. Done: values visible
  in the demo app's info screens, checked by an e2e test.

## M2 and later

See `PLAN.md` section 4. Tasks get split here when M1 is done.
