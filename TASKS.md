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
- [x] **T1.4b Reconnection.** No fix needed: the mux server accepts a new
  session after the app closes one (checked with two force-stop/restart
  rounds, the controller is rediscovered each time). The earlier "no
  reconnection" was the demo not auto-connecting (T1.4c).
- [x] **T1.4c Demo auto-connect for tests.** The init script also sets
  `gsdk_auto_connection_at_startup=true` (property `-PautoConnect`).
  `make sdk-demo-e2e` builds `out/groundsdkdemo-e2e.apk`; `make demo-run`
  installs it (default `DEMO_APK`) and the app connects to the controller
  and the Disco with no tap. The debug signing key is kept in Docker volume
  `disco-fpv-android`; `demo-run` uninstalls first on a signature mismatch.
- [x] **T1.5 Telemetry.** `fake-sc2` keeps the Disco's telemetry and
  exposes a line-based control socket on `127.0.0.1:4322` (`--ctrl-port`):
  `battery`, `rc_battery`, `altitude`, `attitude r p y` (rad),
  `speed vx vy vz` (NED m/s), `airspeed`, `gps lat lon alt`, `gps_lost`,
  `satellites`, `flying_state <name>`, `send_all`, `ping`; answers `ok` /
  `error ...`. Values are sent on change and with the drone's AllStates
  answer (so a late-connecting app gets them). YAML scenarios move to the
  TypeScript runner (T1.6), which drives this socket. Done: values shown by
  the demo's Disco screen, read through a UIAutomator dump: battery 73 %,
  altitude 42.50 m, vertical speed 0.50 m/s, pitch -2.9°, roll 5.7°,
  heading 90°, FLYING, GPS fixed 45.1885/5.7245/250 m with 10 satellites,
  ground speed 12.37 m/s, air speed 15.20 m/s.
  Screenshot `docs/screenshots/t1.5-disco-telemetry.png`.
- [x] **T1.6 TypeScript e2e runner.** `tests/e2e` (Node >= 22.18 with
  type stripping, strict TypeScript, `yaml` 2.8.1): `src/run.ts` plays YAML
  scenarios (`tests/scenarios/*.yaml`) with steps `send`, `wait`,
  `restart_app`, `tap_text` (exact, then substring), `back`, `expect_texts`
  (substring, polling, optional `scroll` through long screens),
  `screenshot`; drives fake-sc2's control socket and the emulator through
  `docker exec ... adb` (UIAutomator dumps); on failure saves a screenshot
  and the screen text; writes `summary.md` and `results.json`.
  `make e2e-unit` type-checks and runs 7 unit tests in a `node:22.18.0`
  container (npm deps installed there with `--ignore-scripts`);
  `make e2e` runs the scenarios on the host into `out/<run>/`, with
  `out/latest` pointing at the last run. Done: `demo-disco-telemetry.yaml`
  passes (9 steps, including a live battery change), and fails with
  `missing text(s): "74 %"` plus evidence files when an expectation is
  changed.

M1 done (2026-11-18). Note: `make check` still stops at `app` (M3).

## M2. Video

How the app receives video through a SkyController 2 (pdraw
`StreamDemuxerMux`, URL `mux/arstream2`): it opens mux channels 4
(`STREAM_DATA`, raw RTP packets) and 5 (`STREAM_CONTROL`, RTCP) and feeds
them to Parrot's stream receiver (`vstrm`). Channel 6 (RTP over RTSP) is
the Anafi path. libmux only logs a remote channel open: the controller
side must open 4 and 5 itself to send on them.

- [x] **T2.1 Video source.** `docker/video-src` (Debian bookworm,
  GStreamer good/bad/ugly + `gstreamer1.0-x` for text overlays):
  `videotestsrc` 856x480 at 30 fps, "FAKE DISCO" label and running clock,
  x264 baseline (zerolatency, 1500 kbit/s, keyframe every 30 frames,
  SPS/PPS repeated), `rtph264pay pt=96 mtu=1400` through `rtpbin` to
  `127.0.0.1:55004`, RTCP to 55005. Env `PATTERN`, `BITRATE`, `LABEL`,
  ports. `make video` / `make video-stop` (container `disco-fpv-video`).
  Done: ~360 RTP packets/s at ~1.5 Mbit/s, RTCP sender reports every ~5 s.
- [x] **T2.2 Forward video into mux.** `fake-sc2` binds UDP
  `127.0.0.1:55004/55005` (`--video-port`), opens mux channels 4 and 5 for
  each session and forwards datagrams once the app has sent
  `VideoEnable=1` (answered with `VideoEnableChanged`); otherwise drops
  them (counted). Done: demo HUD view plays the pattern with the SDK's HUD
  on top (GPS position from the fake), clock advances 32.477 → 34.911 s
  across two screenshots; app logs show `StreamDemuxerMux` started, SPS/PPS
  found, `c2.goldfish.h264.decoder` created. One `pdraw_open_resp
  err=-71 (Protocol error)` logged at start without visible effect: to
  investigate if it matters later. Screenshot
  `docs/screenshots/t2.2-hud-video.png`.
- [ ] **T2.3 Headset view.** The demo's HMD view shows the pattern in both
  eyes. Done: screenshot.
- [ ] **T2.4 Video e2e checks.** Runner step(s) checking video from
  screenshots: content present (not black/uniform) in given regions,
  frames changing between two screenshots, left/right similarity for the
  headset view. Scenario `demo-video.yaml`. Done: passes, and fails when
  the video source is stopped.

## M3 and later

See `PLAN.md` section 4. M3's fast tests (`MockArsdkCore`) can start in
parallel.
