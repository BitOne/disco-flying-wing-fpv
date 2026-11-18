# Tasks

Live backlog for the agent loop (see `PLAN.md` section 5). Work the first
open task. Each task has a done-criterion. Mark `[x]` when done, `[!]` when
blocked (with a one-line reason and date).

## M0. Toolchain (run interactively, not in the loop)

- [x] **T0.1 Repo skeleton.** `Makefile` with stub targets (`sdk`, `app`,
  `unit`, `fake`, `emu`, `e2e`, `check`), `docker/`, `out/` ignored.
  Done: `make help` lists the targets; unimplemented targets fail with the
  task id, so `make check` can't pass by accident.
- [x] **T0.2 SDK build image.** `docker/sdk-build/Dockerfile` (Ubuntu 20.04,
  `repo`, Python 2 and 3, JDK 8 for AGP 3.6.1 / Gradle 6.2.2, JDK 17 only
  for `sdkmanager`, NDK `21.4.7075529`, platform 28, build-tools 28.0.3,
  host-matching `builder` user). Done: `make sdk-image-check` asserts the
  versions. Image size 7.6 GB, mostly the NDK.
  Note for T0.5: the fork's Gradle build still uses `jcenter()`, which is
  read-only; dependencies may need another repository.
- [x] **T0.3 SDK checkout.** Script `scripts/sdk-sync.sh` (`make sdk-sync`):
  `repo init` on the Unified GroundSdk manifest at
  `9b821bfd593884c7f6408ebb61fab700f1b7b0aa`, `repo sync` into `sdk/`
  (ignored by git). Done: 37 projects, 423 MB; every project pinned to a
  commit hash in `sdk.lock.xml` (committed); later syncs use the lock.
  The manifest's Parrot EULA was reviewed: see `NOTES.md` section 16.
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
