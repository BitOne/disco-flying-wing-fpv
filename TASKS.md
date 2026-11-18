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
  Note: the fork's Gradle build uses `jcenter()` (read-only); it still
  worked in T0.5, but it's a future breakage risk.
- [x] **T0.3 SDK checkout.** Script `scripts/sdk-sync.sh` (`make sdk-sync`):
  `repo init` on the Unified GroundSdk manifest at
  `9b821bfd593884c7f6408ebb61fab700f1b7b0aa`, `repo sync` into `sdk/`
  (ignored by git). Done: 37 projects, 423 MB; every project pinned to a
  commit hash in `sdk.lock.xml` (committed); later syncs use the lock.
  The manifest's Parrot EULA was reviewed: see `NOTES.md` section 16.
- [x] **T0.4 Native build.** `make sdk-native` runs Parrot's
  `./build.sh -p groundsdk-android -t build-jni` for `SDK_ABIS` (default
  `x86_64 arm64-v8a`). Done: `libsdkcore.so` + `libc++_shared.so` per ABI in
  `sdk/out/groundsdk-android/jni/sdkcore/libs/`, NDK r21e, API 24. Needed
  cmake, autotools, nasm/yasm in the image, and the Parrot EULA acceptance
  marker (`NOTES.md` section 16).
- [x] **T0.5 Demo APK.** `make sdk-demo` runs Gradle
  (`:groundsdkdemo:assembleDebug`, AGP 3.6.1 / Gradle 6.2.2) in the
  container and copies the APK to `out/groundsdkdemo-debug.apk`. Done:
  `com.parrot.drone.groundsdkdemo`, minSdk 24, targetSdk 28, 22 MB, built in
  48 s. JCenter still served every dependency (2026-11-18). Note: the APK
  declares `armeabi-v7a` and `x86` only because of `libconscrypt_jni.so`;
  `libsdkcore.so` exists for `x86_64` and `arm64-v8a` only, so our app must
  set `abiFilters`.
- [x] **T0.6 Emulator.** `docker/emulator` image (Ubuntu 22.04, emulator,
  `system-images;android-35;google_apis;x86_64`, AVD `disco-test` Pixel 6,
  swiftshader GPU). `make emu` runs it detached as `disco-fpv-emu` with
  `--device /dev/kvm --group-add <kvm gid> --network host` and waits for
  `sys.boot_completed`, then disables animations and keeps the screen on.
  `make emu-stop`, `make emu-screenshot`. Done: Android 15, ABIs
  `x86_64,arm64-v8a`, 1080x2400, boot in 43 s, home screen screenshot.
  Android 15 matches the Fairphone 5. Cold boot each run (no snapshot yet).
- [x] **T0.7 Demo on emulator.** `make demo-run` installs
  `out/groundsdkdemo-debug.apk`, launches `DeviceListActivity` (cold start
  5 s) and takes a screenshot. Done: "GroundSdk Demo" with empty "Remote
  controls" and "Drones" tabs, no crash in the crash log buffer. The debug
  mux bridge address is empty by default (DNS-SD discovery only); M1 must
  set `arsdk_debug_usb_bridge_address` to `10.0.2.2`.

Note: both images accept the Android SDK licences non-interactively
(`sdkmanager --licenses`), the usual practice for CI images.

## M1. Fake controller connects

- [x] **T1.1 Build the C example drone for the PC.** `make fake-build`
  builds Alchemy modules `arsdk-ng-device` and `arsdk-ng-controller` with the
  SDK's `olympe-linux` product, without touching `sdk/`: our
  `fake-sc2/host-libs/atom.mk` declares system `zlib` and an empty `python`
  module (added through `ALCHEMY_TARGET_SCAN_ADD_DIRS`), unneeded SDK
  packages are excluded (`ALCHEMY_TARGET_SCAN_PRUNE_DIRS`), and
  `USE_CONFIG_CHECK=0` skips the saved-config check. Image gained
  `zlib1g-dev`. Binaries in `sdk/out/olympe-linux/staging/usr/bin/`.
- [x] **T1.2 Demo app reaches the unmodified example.** Gradle init script
  `scripts/gradle/debug-bridge.init.gradle` sets the demo's
  `arsdk_debug_usb_bridge_address`/`_port` (default `10.0.2.2:4321`; the
  example's mux port is hard-coded to 4321). Example run with
  `--mux --no-publisher-avahi --no-publisher-net` in a host-network
  container. Result: the app logs "Connected to debug bridge, starting MUX
  backend and discovery", and both sides open mux channels 2 and 3. No
  device appears: mux discovery (`libarsdkctrl/src/mux/arsdk_discovery_mux.c`,
  `is_devtype_supported`) drops types outside the app's USB list, and the
  example announces `ARSDK_DEVICE_TYPE_BEBOP_2`. Fixed by T1.3/T1.4.
  Also: `device.c` parses `--replay-file` but never uses it.
- [x] **T1.3 `fake-sc2` source in our repo.** `fake-sc2/src/fake_sc2.c`
  (copy of `device.c`, Parrot licence header kept) built as our Alchemy
  module `fake-sc2` (`fake-sc2/atom.mk`, which also includes
  `host-libs/atom.mk` since Alchemy doesn't scan below a directory with an
  `atom.mk`). Added `--mux-port` (default 4321). `make fake` starts it in
  container `disco-fpv-fake` (host network), `make fake-stop` saves its log.
- [x] **T1.4 SkyController 2 + Disco identity.** Publisher announces
  `ARSDK_DEVICE_TYPE_SKYCTRL_2` "SkyController 2 (fake)", serial
  `PI040443FAKESC2`. Replies (from `RCController`/`DeviceController`):
  `Skyctrl.Settings.AllSettings` → `Skyctrl.SettingsState.ProductVersionChanged`
  + `AllSettingsChanged`; `Skyctrl.Common.AllStates` →
  `Skyctrl.DeviceState.ConnexionChanged(CONNECTED, "DISCO-FAKE01", 0x090e)`
  + `Skyctrl.CommonState.AllStatesChanged`; drone side
  `Common.Settings.AllSettings` / `Common.Common.AllStates` answered the
  same way. Done: demo shows the controller CONNECTED (USB-LOCAL) and
  "DISCO-FAKE01 DISCO CONNECTED" via the controller; the app then sends
  `Ardrone3.MediaStreaming.VideoEnable=1` and a steady `Ardrone3.Piloting.PCMD`
  stream. Screenshots in `docs/screenshots/t1.4-*`.
- [ ] **T1.4b Reconnection.** After the app closes the mux session, the
  example doesn't accept a new one (restarting the fake is needed). Fix the
  mux server so the app can disconnect and reconnect. Done: force-stop and
  restart the app twice, it reconnects each time without restarting the fake.
- [ ] **T1.4c Demo auto-connect for tests.** The demo needs two taps to
  connect; find a non-UI way (intent, auto-connection setting, or our own
  test app in M3) so e2e runs don't depend on screen coordinates.
- [ ] **T1.5 Telemetry from a scenario file.** `fake-sc2` reads a YAML
  timeline (battery, altitude, attitude, speed, airspeed, GPS) and sends the
  Disco events through the drone manager. Done: values visible in the demo
  app's drone info screen.
- [ ] **T1.6 TypeScript e2e runner.** `tests/e2e` (Node LTS, strict
  TypeScript): starts the fake with a scenario, drives the emulator through
  `adb`, reads UI text (UIAutomator dump) and takes screenshots, writes
  `out/<run>/summary.md`. `make e2e`. Done: one scenario passes and fails
  correctly when a value is changed.

## M2 and later

See `PLAN.md` section 4. Tasks get split here when M1 is done. M3's fast
tests (`MockArsdkCore`) can start in parallel with M1/M2.
