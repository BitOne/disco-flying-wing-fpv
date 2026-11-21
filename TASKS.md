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

M1 done (2026-11-18).

## M1b. Validate the fakes against FreeFlight Pro (user request, 2026-11-18)

FreeFlight Pro 5.2.7 (community "Legacy Edition", static-checked, run only
in the disposable emulator) starts on the Android 15 x86_64 emulator
through ARM translation. The user accepted its conditions of use
(`NOTES.md` section 16). FreeFlight reaches a SkyController 2 only through
a real USB accessory (`UsbManager.openAccessory`), which the emulator
can't provide, so it validates the **fake Disco over Wi-Fi** only. Its
Wi-Fi discovery is mDNS `_arsdk-090e._udp` with a raw TXT
`{"device_id":...}` (it crashes without it), then the ARSDK
handshake on the resolved address and port. The emulator can't forward
guest UDP to the host, so the fake Disco runs inside the emulator.

- [x] **T1.7 fake-sc2 for Android.** Build the `fake-sc2` module for
  x86_64 Android with the SDK's `groundsdk-android` product
  (`make fake-android`); `host-libs` only for the native Linux build.
  Done: binary and libraries run from `adb shell` in the emulator.
- [x] **T1.8 Disco mode.** `--disco`: network backend on TCP 44444,
  Disco identity (`EVINRUDE`, "DISCO-FAKE01"), Wi-Fi handshake JSON with
  ARStream2 server ports, video sent as RTP to the client's stream port
  from its handshake; input port bindable on all interfaces.
- [x] **T1.9 mDNS helper.** Debug helper app (Kotlin) registering
  `_arsdk-090e._udp` "DISCO-FAKE01" port 44444 through Android NSD.
- [x] **T1.10 FreeFlight run.** `make emu`, then
  `make freeflight FREEFLIGHT_APK=<your copy>` (builds T1.7/T1.9, starts
  the fake camera on host UDP 56004/56005 redirected into the emulator,
  the fake Disco, the mDNS announce, installs and launches FreeFlight),
  then `make e2e SCENARIOS=tests/scenarios/freeflight-*.yaml
  E2E_CTRL_PORT=4422`, `make freeflight-stop` afterwards. Terms must be
  accepted once by hand in a fresh emulator. Result: FreeFlight lists
  "Disco" (battery from the fake), shows the live test pattern on the home
  tile and full screen, and its HUD follows the fake's telemetry (speed,
  altitude, battery, GPS, flying state). One fix needed: magnetometer
  calibration states (otherwise "Please calibrate your drone"). Expected
  gap: "A remote controller is required to pilot the drone" (Wi-Fi only,
  no SkyController). Altitude is drawn without a UI text node, so the
  scenario checks speed and battery. Screenshots:
  `docs/screenshots/m1b-freeflight-*.png`.

- [x] **T1.11 Flight states.** The fake reacts to piloting commands the
  way a Disco does (flight simulation in `fake_sc2.c`, `flight_tick`):
  TakeOff gives motor ramping, then after the throw (automatic after 2 s,
  or `throw` on the control socket) taking off while climbing at 5 m/s to
  50 m, then hovering (the Disco circles). UserTakeOff(1) arms a hand
  launch and waits for `throw`. Landing descends at 4 m/s to landed.
  Emergency cuts the motor and lands after 1 s. NavigateHome(1) reports
  return home in progress for 5 s then finished (circling), NavigateHome(0)
  stops it. Home position (`HomeChanged`) set at the first GPS fix or with
  `home`. The control socket's new `state` query reports
  `<flying state> alt=<m> home=<0|1> pcmd=<n>`; telemetry set by hand
  (altitude, airspeed, flying_state) stops the simulation, so scripted
  scenarios are unchanged. Runner: `expect_fake` (poll a control query),
  `tap` (screen fractions), `tap_id` (resource id). A controller
  reconnecting over Wi-Fi now replaces the stale session instead of being
  rejected until it times out. Checks:
  `tests/scenarios/freeflight-piloting.yaml` (FreeFlight's return home,
  STOP RTH and LANDING buttons; FreeFlight has no take-off without a
  SkyController) and `tests/scenarios/demo-piloting.yaml` (GroundSdk demo
  through the fake SkyController 2: take-off, return home, landing,
  take-off then emergency), both passing. FreeFlight shows altitude only
  with a GPS fix, and the distance from the phone, so `make freeflight`
  puts the emulator's location at the scenarios' home (68 m shown, as
  computed).

Update 2026-11-19: the SkyController 2 side is now validated with
FreeFlight too, over the emulated USB accessory
(`tests/scenarios/freeflight-usb.yaml`, `NOTES.md` section 20), flying the
simulated Disco up to its Cockpitglasses mode.

M1b done (2026-11-18): the fake Disco's protocol (discovery, handshake,
connection states, telemetry, ARStream2 video) satisfies Parrot's own app.
The SkyController 2 path of the fake is still validated only by GroundSdk
(our app and the SDK demo), since FreeFlight needs real USB.

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
- [x] **T2.3 Headset view.** Resolved 2026-11-18 (during M4). Root cause:
  GroundSdk's HMD shaders are `#version 300 es` but declare uniforms with
  `layout (location = N)`, which GLSL ES only allows from 3.10. Strict
  compilers (SwiftShader, ANGLE) fail to link them; every frame raised
  `GL_INVALID_OPERATION` in `LensProjection.draw` (found with per-step
  `glGetError` checks), so the view stayed black. Fixed by
  `patches/packages/groundsdk-android/0001-hmd-glsl-es-300-uniform-locations.patch`
  (plain uniforms + `glGetUniformLocation`), applied by `make sdk-sync`.
  Lenient phone drivers may have hidden the bug; the fix makes it portable.
- [x] **T2.4 Video e2e checks.** Runner step `expect_video` (`region`
  as screen fractions, `min_contrast`, `min_change`, `interval`): two
  screenshots `interval` apart, decoded by a dependency-free PNG decoder
  (`src/png.ts`, Node zlib); passes when the region's luminance spread
  (`lumaStats`) and changed-pixel fraction (`changedFraction`) reach the
  thresholds; retries while the screen rotates (size change). Scenario
  `tests/scenarios/demo-video.yaml` (HUD view, needs `make video`). Done:
  passes with the camera running (contrast 73.8, change 79 %), fails with
  it stopped (contrast 48.7 from the HUD graphics, change 0.00 %). 12 unit
  tests. Left-right similarity for the headset view waits for T2.3.
  Note: 79 % change on a mostly static pattern is more than the clock and
  noise patch explain (frame-to-frame encoder noise, or scaling); tighten
  if it ever hides a problem.

M2 done except T2.3 (headset view on the emulator), 2026-11-18.
`make emu` accepts `EMU_GPU` (default `swiftshader_indirect`, also
`swangle_indirect`).

## M3. Our app skeleton

Approach: the SDK keeps its 2020 toolchain (AGP 3.6.1, JDK 8, NDK r21) in
`sdk-build`; it exports its modules as AARs, and our app is a separate,
modern Gradle project (current AGP/Kotlin, target SDK 35, JDK 17) that
consumes them and re-declares their Maven dependencies.

- [x] **T3.1 SDK AARs.** `make sdk-aar` runs `:sdkcore`, `:groundsdk`,
  `:arsdkengine`, `:sdkcore-testing` `assembleRelease` in the SDK container
  (50 s) and copies them to `out/sdk-aar/<module>.aar`; `sdkcore.aar`
  carries `libsdkcore.so` + `libc++_shared.so` for x86_64 and arm64-v8a.
- [x] **T3.2 App project and build image.** `docker/app-build`
  (Temurin JDK 17, Android SDK 35, build-tools 35.0.0, Gradle 8.11.1);
  `app/` Gradle project (AGP 8.7.3, Kotlin 2.1.0), application id
  `io.github.bitone.discofpv`, "Disco FPV", minSdk 24, target 35, ABIs
  x86_64 + arm64-v8a; SDK AARs from `out/sdk-aar` (property `sdkAarDir`)
  plus the SDK's Maven dependencies at the SDK's versions. Manifest marks
  the SDK's `RcAccessoryBootstrapActivity` exported (needed for target
  31+). `MainActivity` (framework Views, no AppCompat) lists the remote
  control and drone with their connection states. `make app`,
  `make app-install`. Done: 16 MB APK, launches on Android 15, no crash.
- [x] **T3.3 Test wiring in the debug build.** `src/debug/res`: debug USB
  bridge on, `10.0.2.2:4321`; main resources: GroundSdk auto-connection on
  (wanted on the real phone too). Done: `tests/scenarios/app-connect.yaml`
  passes: "SkyController 2 (fake) (SKY_CONTROLLER_2): CONNECTED" and
  "DISCO-FAKE01 (DISCO): CONNECTED". Screenshot
  `docs/screenshots/t3.3-app-connected.png`. To fix in T3.4: content draws
  under the status bar (edge-to-edge on target 35).
- [x] **T3.4 Flight screen with 2D HUD.** `FlightActivity` (landscape,
  full screen, immersive, screen on) with `GsdkStreamView` and a text HUD
  (`FLYING`, `ALT 42.5 m`, `AIR 15.2 m/s`, `GND 12.4 m/s`, `BAT 73 %`,
  `RC 58 %`, `GPS 10 sat`), formatting in `HudFormat` (5 JVM unit tests);
  "Flight view" button once a drone is connected. The live stream is
  opened once streaming is reported enabled, and only played when not
  playing. Done: `tests/scenarios/app-flight-hud.yaml` passes 5/5
  alternating with `demo-video.yaml` (video live, HUD values, then live
  updates to `BAT 21 %`, `ALT 120.0 m`, `GPS no fix`, `LANDING`), and fails
  with the camera stopped. Screenshot `docs/screenshots/t3.4-flight-view.png`.
  The long hunt for "no video in our app" ended with two rig problems, not
  app problems:
  1. **Periodic IDR frames in the fake camera.** An IDR resets H.264
     `frame_num`; Parrot's receiver flags that as missing frames
     (`vstrm_rtp_h264_rx_check_missing_frames`, "TODO: handle reset to 0 for
     IDR frame") and the decoder then hides frames: black or frozen video,
     at random, in both apps. Fixed by x264 periodic intra refresh
     (`docker/video-src`), like Parrot's drones.
  2. **Test region missing the moving parts.** Our full-screen video put
     the clock and noise patch outside the checked region; the pattern now
     scrolls (`SPEED`, default 4 px/frame) so any region moves.
  Experiments that did not matter (kept for the record): target SDK 28,
  theme/immersive flags, view size, debug vs release AARs, legacy JNI
  packaging, transitive R classes, orientation, launching the screen
  directly. Bisection that located it: our stream code worked inside the
  demo app, and the demo also failed intermittently with debug logs on.
- [x] **T1.4d fake-sc2: new connection replaces the old session.** Found
  while testing T3.4: an app killed on the emulator leaves its mux session
  open until pings time out, and Parrot's example refused new connections
  meanwhile, so the next app got no or a broken connection. Now a new mux
  connection closes the previous session (the real controller has one USB
  port). Demo and our app alternate reliably.
- [~] **T3.5 Fast tests on `MockArsdkCore`.** Deferred 2026-11-18. Findings:
  `sdkcore-testing.aar` contains `MockArsdkCore`, `Expectation`,
  `ExpectedCmd` and the generated `ArsdkEncoder` (drone events). Parrot's
  harness (`ArsdkEngineTestBase`) wasn't published. Hook:
  `ArsdkEngine.createArsdkCore()` is package-private "to allow mocking";
  a debug-only subclass in package `com.parrot.drone.groundsdk.arsdkengine`
  (app debug sources, same class loader as the AAR) registered through the
  `com.parrot.drone.groundsdk.engine.arsdkengine` manifest meta-data can
  return a `MockArsdkCore`. The mock enforces a strict ordered queue of
  expected connects and commands (controller date/time, AllSettings,
  AllStates, then the proxied drone's Common date/time, AllSettings,
  AllStates, VideoEnable, ...), so each test needs that full sequence.
  The e2e suite already covers connection, HUD and video; revisit when app
  logic grows beyond display.

## M4. Headset view

- [x] **T4.1 Headset screen.** `HeadsetActivity` (landscape, full screen,
  immersive) with `GsdkHmdView`: live stream per eye with lens distortion
  correction, our HUD (`hud.xml`) as the HMD overlay in both eyes.
  "Headset view" button on the main screen. Refactor: `DroneScreenActivity`
  (drone lookup, screen on, immersive, `withLiveStream`), `HudBinder`
  shared by flight and headset screens. Done:
  `tests/scenarios/app-headset.yaml` (live video in left and right eye
  areas) passes 3/3 on SwiftShader (also on ANGLE); `app-flight-hud.yaml`
  still passes. Screenshot `docs/screenshots/m4-headset-view.png`.
- [x] **T4.2 HUD layout for the headset.** `hud_headset.xml`: the HMD
  overlay covers the lens render area (54 x 41 mm, `Renderer.java`) and
  the 16:9 video is letterboxed in it, leaving a band of about 5 mm above
  and below the picture. Flight data (state, altitude, air speed) goes in
  the top band, systems (battery, RC battery, GPS, ground speed) in the
  bottom band, one centred line each, so the video stays clear. The text
  is sized from the overlay's width (`HeadsetHudSizer`, unit-tested): the
  overlay has a physical size, so a fixed sp size that fits one phone
  overflows another; 40 monospace characters per line plus gaps (longest:
  "EMERGENCY LANDING  ALT 120.0 m  AIR 15.2 m/s"). The flight view keeps
  `hud.xml`. Screenshot `docs/screenshots/m4-headset-hud.png`.
- [x] **T4.3 Lens settings.** `LensSettings` (left and right lens offsets
  0..1 by 0.05, height -10..+10 mm by 0.5, unit-tested), saved in
  SharedPreferences and applied to `GsdkHmdView` on start. A tap on the
  headset view shows the calibration panel (-/+ per value, Done), changes
  apply live. Check: `tests/scenarios/app-lens.yaml` (panel, values,
  still the same picture in both eyes, values restored after an app
  restart, defaults put back). Probe: both offsets at 1.0 move the right
  eye from +0.427 to +0.468 of the screen width. Screenshot
  `docs/screenshots/m4-lens-panel.png`.
- [x] **T4.4 Headset checks.** Runner steps `expect_stereo` (finds the
  horizontal shift mapping the left eye onto the right one and checks the
  pictures match: mean luminance difference, 0.0 measured) and
  `expect_image` (content in a region, used for the HUD lines in the four
  bands), both unit-tested. `app-headset.yaml` uses them.

Rig notes from M4: once, after about two hours of use (and a `geo fix`
feed from the FreeFlight setup), the emulator's system server hung in the
location service and was restarted; the app's main thread, blocked in a
location call, never showed the drone connected. Not reproduced on a fresh
emulator. A launcher "isn't responding" dialog right after a cold boot on a
loaded host covered the app: `emu-start.sh` now sets `hide_error_dialogs`
(crashes and ANRs are to be caught from logcat instead, M5).

M4 done (2026-11-18), except entry and exit gestures from PLAN.md, left
for the real headset (checkpoint B): the headset view is entered from the
main screen and left with Back.

`make check` is green end to end since 2026-11-18: app build, JVM unit
tests, fake controller and camera, emulator, install, and the app
scenarios (`tests/scenarios/app-*.yaml`: connection, flight HUD with live
video, headset view). Demo-app scenarios run with
`SCENARIOS="tests/scenarios/demo-*.yaml"` after `make sdk-demo-e2e demo-run`.

## M4b. HUD modes (user request, 2026-11-18)

- [x] **T4.5 Modes.** `HudModel` (all HUD data, pure Kotlin) fed by
  `HudBinder` from the SDK (now also attitude, compass heading, GPS
  position, home from the return-home interface); `Hud` renders it in the
  current `HudMode`: CLASSIC (the text readouts), MINIMAL (`MinimalHudView`:
  arrow pointing home relative to the nose with the distance, battery;
  in the top and bottom bands, so also clear of the video in the headset),
  FIGHTER (`FighterHudView`: artificial horizon with a pitch ladder that
  rolls and moves with the attitude around a fixed aircraft symbol, speed
  tape on the left, altitude tape on the right, heading tape at the top
  with a home marker and distance, battery, state and GPS at the bottom).
  Same views in the flight view and in both eyes of the headset, sized
  from the view. Geometry (distance, bearing, relative bearing) and tape
  arithmetic unit-tested. HOVERING shows as CIRCLING (a flying wing holds
  position by circling).
- [x] **T4.6 Controller button.** While the flight or headset view is in
  front, the app grabs the SkyController 2's SETTINGS button
  (`HudButton`, Mapper grab): each press goes to the next mode, saved for
  the next sessions; the button is given back when leaving. A tap on the
  flight view also cycles (phone use). SETTINGS has no flight action on
  the Disco; which physical button it is gets confirmed at checkpoint A
  (`HudButton.BUTTON`, one constant).
- [x] **T4.7 Fake support.** Mapper `Grab` answered with `GrabState`;
  `button <name>` sends a press then a release 150 ms later (only for a
  grabbed button); the circling Disco flies an 80 m circle around home
  (heading turning, bank angle from the turn, position moving with a GPS
  fix), pitch up when climbing and down when landing; `circle` starts it
  directly. Check: `tests/scenarios/app-hud-modes.yaml` (cycle by button
  and by tap, descriptions, persistence, headset in fighter mode with the
  same picture in both eyes, button released on the main screen); runner
  step `shell` (scenarios reset the saved HUD mode). Screenshots
  `docs/screenshots/hud-*.png`.

Found on the way: the drawn views first set their content description on
every update (heading changing ten times a second), and the stream of
accessibility events made UI dumps return stale trees, failing the
scenario one run in three; now set only when it changes.

## M5b. Real USB path in the emulator (2026-11-19)

- [x] **T5.6 USB accessory emulation.** `make check-usb`: the emulator in
  USB mode (`make emu-usb`: `-writable-system`, accessory feature,
  configfs gadget on `dummy_udc.0`, `aoa-host`), the app built without
  the debug bridge (`make app-usb`, build type `usb`), all app scenarios
  green through Android's USB accessory path, including unplug and replug.
  The first plug's "Open Disco FPV to handle ...?" dialog is accepted by
  the setup script ("Always"). See `NOTES.md` section 20.

## M5. Robustness

- [x] **T5.1 Fake controller faults.** Control socket `drone_link
  lost|ok` (the SkyController reports the Disco out of range and back,
  `ConnexionChanged`; no video meanwhile) and `unplug <s>` (the phone's
  mux session is closed and new ones refused for that long, like pulling
  the USB cable; GroundSdk's bridge retries every second).
- [x] **T5.2 Link loss in the app.** HUD: LINK LOST (warning colour)
  instead of the flying state while the drone is not connected, values
  cleared to `--` by the SDK. Video: `withLiveStream` releases the stream
  when the drone disconnects or its stream server goes away, and opens it
  again when it is back (before, the picture froze on the last frame and
  never resumed); the flight view hides its stream view meanwhile, since
  GsdkStreamView keeps the last frame. Low battery (20 % or less, drone
  and controller) in the warning colour. Check:
  `tests/scenarios/app-link-loss.yaml` (LINK LOST, blank video, telemetry
  and live video back, low battery, GPS loss).
- [x] **T5.3 Unplug, background, rotation.** MainActivity `singleTop`
  instead of `singleTask`, so the launcher brings back the flight or
  headset view instead of clearing it. Check:
  `tests/scenarios/app-unplug.yaml` (main screen rotated both ways;
  headset view blank while unplugged, live in both eyes once plugged back;
  home then launcher: video resumes). The runner now starts the app as the
  launcher does, and has `home`, `resume_app`, `rotate` and
  `expect_image max_contrast` steps.
- [x] **T5.4 No crash or ANR.** Each scenario fails if the app under test
  crashed or had an ANR (crash and events logs, cleared at the start).
- [~] **T5.5 Fast tests on MockArsdkCore** for the same cases: deferred
  with T3.5 (strict expectation queue); the rules are unit-tested in
  `HudFormat` (state line, low battery), the behaviour end to end.

M5 done (2026-11-18): `make check` green with the six app scenarios.

## M6. Realistic flight data (done 2026-11-19)

- [x] **T6.1 Simulated Disco.** `make sim-image`, `make sim` / `sim-stop`,
  `make sim-unit`: ArduPilot SITL (ArduPlane, flying wing) for the flight,
  FlightGear on the host GPU (VirtualGL) for the camera, TypeScript bridge
  to fake-sc2 (`sim external`, `events`, `position`, `rth`). Check
  `tests/scenarios/sim-flight.yaml`. See `NOTES.md` section 21.
  Screenshots `docs/screenshots/sim-*.png`.
- [x] **T6.2 Piloting the simulation from the app side.**
  `tests/scenarios/sim-piloting.yaml` (`make sim SIM_ARGS=`, demo app):
  take-off (TAKEOFF, then circling at 50 m), return home (RTL) and its
  cancellation (LOITER), landing (mission: approach point 250 m out at
  30 m, then NAV_LAND at home: 8.6 degree glide, touchdown at 0.2 m/s,
  disarmed), all through the fake SkyController 2 and the bridge. The
  demo's screens update continuously with live data and UIAutomator then
  can't dump them ("could not get idle state", which used to leave the
  previous dump in place and fool the runner: now an error); its buttons
  are tapped by position.

- [x] **T6.3 The real Disco's features in the fake** (user request,
  2026-11-19). From the Disco firmware 1.7.1 (commands its flight program
  decodes and events it sends, default settings, flight controller
  parameters) and FreeFlight Pro's use of them: settings, states, media,
  calibrations, Wi-Fi, alerts, flight meter, flight plan states in
  `fake-sc2/src/disco.c`; manual flight with the sticks, loiter button,
  navigating return home (asked, link lost, critical battery), circular
  landing, geofence, battery drain in the flight simulation; the same
  through the simulator bridge (FBWB with RC overrides, circling settings
  as ArduPlane parameters). `make fake-test` (in `make check`): protocol
  tests with `disco-probe`. Fake mux socket without Nagle (AllStates
  5.6 s to 63 ms). See `NOTES.md` section 22, including what is still
  missing (flight plan files over FTP, media download, RC receiver).

- [x] **T6.4 FreeFlight, the controller's mapper, failures, map** (user
  request, 2026-11-19). FreeFlight decompiled and driven through all its
  screens over the emulated USB link: 34 commands, all handled but two the
  real Disco ignores too. Fake SkyController 2 mapper (`sc2.c`: mappings,
  grab, axes, sliders, access point, co-piloting), `fail` for the Disco's
  failures, `unhandled`. App: warnings, moving map (mode and map button),
  map tiles (OpenStreetMap, IGN photos, custom XYZ; offline cache and
  download ahead), last GPS position kept, camera tilt (fake, simulator
  view, HUD horizon), settings screen, headset HUD in the middle of the
  video. Scenarios `app-failures`, `app-map`, `app-map-tiles`,
  `app-camera-tilt`; probes `sc2-mapper`, `disco-failures`. See
  `NOTES.md` section 23 (including the USB link's latency with
  FreeFlight).

- [x] **T6.5 Head tracking** (user request, 2026-11-19): in the headset,
  the phone's orientation points the Disco's digital camera (tilt and
  pan); centre indicator in the HUD; recentre button (A by default);
  simulator view follows (FlightGear pitch and heading offsets).
  `app-head-tracking` (tilt through the emulator's virtual accelerometer;
  its fusion ignores gyroscope overrides, so pan is unit-tested).
- [x] **T6.6 Simulator view** (`NOTES.md` sections 24, 25): FlightGear,
  Gazebo and CesiumJS tried and compared. `make sim SIM_ENGINE=gazebo`:
  Gazebo Harmonic's physics (ArduPilot's Zephyr) and a 3D world of houses
  and trees, the nose camera streamed to fake-sc2, a hand launch; `make
  sim-view`: line-of-sight (or chase) view on the PC's screen. CesiumJS
  probe in `sim/cesium` (real place, not wired to the bridge).
- [x] **T6.8 Offline OpenStreetMap map** (`NOTES.md` section 26): a
  Mapsforge TileSource rendering the moving map's tiles from a local
  region .map file (download.mapsforge.org, ODbL), so the map works with no
  network at the field; OSM's tile server forbids prefetching, the data
  does not. Done: "offline map file" background, the file picked in the
  settings; `make map-test`, `tests/scenarios/map-offline.yaml`. Later:
  download a region from the app; hillshading.
- [x] **T6.9 Satellite view worldwide** (`NOTES.md` section 27): the
  "satellite (best available)" background: national orthophotos (France,
  Switzerland, Spain, Netherlands, USA) else Sentinel-2 cloudless 2016,
  per tile, downloadable ahead. Later: more countries (Austria, Belgium,
  German states).
- [ ] **T6.7 Gazebo follow-ups**: ~~the camera tilt and pan~~ (done: a
  gimbal driven by gz-helper; a textured ground with fields, a pond,
  hedgerows); a world generated from a real place (OSM buildings and IGN
  photos as the ground, the CesiumJS probe's data, with the Fuel trees and
  houses); a model closer to the Disco (750 g, 1.15 m) than the Zephyr
  (1.9 kg); `make e2e` against the Gazebo camera.

Known gap: on the ground before take-off, the simulator's camera can sit
just below FlightGear's terrain (SITL home altitude 3 m vs the scenery's
elevation there), so the near ground isn't drawn; fine once airborne.

Known flake (2026-11-19): `app-unplug.yaml` failed twice in about 25 runs
(video not back after the replug or after resume). Not reproduced in 13
runs after fake-sc2 started streaming by default in SkyController mode,
as the real SC2 does (GroundSdk's VideoEnable is a no-op there). Watch.

## M7. Release (started)

- [x] **T7.1 16 KB pages.** `libsdkcore.so` (NDK r21) was 4 KB aligned on
  arm64, and so was the NDK's `libc++_shared.so`. SDK patch
  `patches/packages/groundsdk-android/0002-sdkcore-static-stl-16k-pages.patch`:
  link with `-z max-page-size=16384` and libc++ statically (libsdkcore is
  the only C++ library). arm64: libsdkcore 16 KB, conscrypt 64 KB; APK
  `zipalign -c -P 16` OK. `make apk-check` (part of `make check`) fails if
  an arm64 library drops below 16 KB. x86_64 (emulator only) not checked.
  `make check` green with the rebuilt libraries.
- [ ] **T7.2 CI.** GitHub Actions running `make check` (KVM runner).
- [x] **T7.3 Signed release APK.** `make release-key`, `make release`
  (`NOTES.md` section 32); v0.1.0 on GitHub.
- [x] **T7.4 README** with the current state, build and test commands.

## M8. Before the first real flight (risk review, 2026-11-20)

From `NOTES.md` section 29 (ranked risks) and section 28 (measurements).

- [x] **T8.1 SDK USB permission crash.** Done (`NOTES.md` section 30):
  patch 0003, scenario `usb-permission.yaml` in `make check-usb`. `ArsdkUsbMuxBackendController.
  requestRcPermission` registers its receiver without
  `RECEIVER_NOT_EXPORTED` (SecurityException on Android 14 and later with
  target 35; seen in the emulator) and builds its PendingIntent without a
  mutability flag (IllegalArgumentException from target 31). Reached when
  the controller is plugged in and the app lacks the permission (launched
  from the launcher, chooser dismissed, FreeFlight picked once). Patch
  `patches/packages/groundsdk-android/0003`, a scenario starting the app
  before the permission on `emu-usb`, and a search of the AARs for other
  receivers and PendingIntents.
- [x] **T8.2 Frozen video watchdog.** Done (section 30): patch 0004
  (frames rendered counted), `VideoWatchdog`, `app-video-frozen.yaml`. Not
  recovered: a camera stream restart (timestamps back), only a new
  connection; the warning shows. No new frame for about 500 ms: a
  loud HUD alert, then restart the stream. Covers a camera stream restart
  (timestamps going back freeze pdraw for good, both apps, section 28),
  the `app-unplug` flake and decoder stalls.
- [x] **T8.3 Safe button grabs.** Done (section 30): `ButtonSafety`; the
  release of grabs when the app dies stays for the bench. Read the controller's mapping before
  grabbing B, A and settings; never grab a button mapped to a flight
  action; check grabs are released when the app dies (bench).
- [x] **T8.4 Telemetry delay.** Done (section 30): SDK 11 to 94 ms, HUD
  renders coalesced (median 155 to 266 ms to the screen, was 381 to 589);
  acknowledged telemetry no longer flooded by the simulator. 0.2 to 0.8 s from the fake to our HUD
  (FreeFlight 0.2 to 0.8 s too), mostly phone side: timestamps in the SDK
  path (mux read, arsdk decode, Java callback, HUD draw) to find where.
- [ ] **T8.5 Headset view safety.** Partly (section 30): the camera
  recentres when head tracking stops. Recentre the camera when head
  tracking stops; head tracking off by default for the first flights;
  phone temperature and battery soak test (30 minutes, `dumpsys
  thermalservice`).
- [ ] **T8.6 Bench session** (propellers off), then the first flight, as
  listed in `NOTES.md` section 29.

- [x] **T8.7 Crashes in the simulator** (user request, section 30): the
  bridge detects impacts and slides to a stop: MOTOR CUT OUT, LANDED; a
  hard impact loses the link (LINK LOST, last position shown). `make
  sim-crash` for a nose-in crash.
- [x] **T8.9 Headset decoder stall.** Found and mitigated (section 30):
  the GL thread blocked in eglSwapBuffers under UI dumps; the runner
  dumps less, the app recreates the screen. `app-unplug` still fails
  about once in six. Was: After about 4 minutes of
  `app-lens.yaml` the decoder stalls in the headset view (its output
  buffers all held) and restarts don't recover it there (`NOTES.md`
  section 30); the scenario fails since the watchdog. Find what holds the
  frames (HMD rendering path), check on a phone.
- [ ] **T8.8 A traditional RC transmitter** (user request, 2026-11-20;
  `NOTES.md` section 31). Steps 1 to 4 done (fake, SDK patch 0005, the RC
  transmitter screen, the HUD's RADIO ON and RADIO LOST, `app-rc.yaml`,
  `disco-rc.probe`); left: the bench (step 5): pilot the Disco with a hobby radio (S.BUS or
  SUMD receiver on the C.H.U.C.K.), set up from our app, while the app
  keeps the video and HUD through the SkyController 2 (or the phone's
  Wi-Fi). Steps:
  1. Fake: the Disco's `Rc` feature (receiver state and protocol, enable
     only when landed, channel monitoring, neutral and min/max calibration
     per action, inversion, supported actions), channels driven from the
     control socket (`rc ch <n> <value>`), and the sticks from the RC
     applied to the flight simulation when enabled (take-control switch).
  2. SDK: GroundSdk has no peripheral for it (only the generated
     `ArsdkFeatureRc`): a patch adding an `RcReceiver` peripheral
     (arsdkengine controller for the Disco), or raw commands from the app.
  3. App: a settings screen "RC transmitter": receiver state and protocol,
     enable (landed only), live channel bars, a calibration wizard
     (neutral, then each action's min/max: roll, pitch, yaw, gaz, and the
     switches: take-off/land, return home, emergency, piloting mode, take
     control), channel inversion; the HUD shows who pilots (RC or
     controller) and the receiver's state; an alert if the receiver is
     lost while it pilots.
  4. Scenarios against the fake; FreeFlight's RC screens as a reference
     (its `Rc.*` commands, section 23).
  5. Bench: receiver wiring (which C.H.U.C.K. port and UART:
     /dev/uart-sbus on ttyPA2, /dev/uart-sumd on ttyPA3), the radio's
     failsafe (no pulses, so the Disco's own link-loss handling applies),
     a frequency apart from the Disco's Wi-Fi (868/915 MHz ELRS or
     Crossfire with S.BUS output, or the Disco on 5 GHz), range test,
     then a flight with the SkyController in hand as a fallback.

## M9. What FreeFlight lacks (user request, 2026-11-21)

From `NOTES.md` section 40 (web research) and the user's go-ahead.

- [ ] **T9.1 Disco settings screen**: geofence (on/off, max altitude, max
  distance), return home (delay after link loss, home type), circling
  (direction, altitude), editable only where the Disco allows it.
- [ ] **T9.2 Wi-Fi region and transmit power**: the Disco's transmit
  power follows its Wi-Fi country (Broadcom regulatory domain) and the
  outdoor flag (firmware 1.7.1, `NOTES.md` section 41). Settings: automatic
  or a chosen country, outdoor on/off, landed only (the Wi-Fi restarts);
  the pilot picks what their regulation allows. SDK patch 0006 (the
  Disco's `Common.Settings.Country/AutoCountry` and
  `Common.WifiSettings.OutdoorSetting`, which GroundSdk's
  WifiAccessPoint does not use).
- [ ] **T9.3 Small fixes**: the "Ready" hint without a SkyController;
  Wi-Fi link quality on the HUD.
- [ ] **T9.4 Flight log on the phone**: telemetry recorded per flight,
  flight list and total time, GPX/KML/CSV export (share sheet).
- [ ] **T9.5 The fake Disco's FTP**: the "drone" mux host, an FTP server
  (TypeScript) on a shared directory, the privileged ports mapped; then
  media, PUD flight data, flight plan upload and firmware upload can be
  tested.
- [ ] **T9.6 Recording and media**: recording state and SD space on the
  HUD; browse, download (progress, cancel), delete; free space checked.
- [ ] **T9.7 Wind estimate and battery to get home** on the HUD.
- [ ] **T9.8 Flight plan editor** (free): waypoints on the offline map,
  MAVLink file upload, start/pause/stop; fake and SITL scenarios.
- [ ] **T9.9 Firmware update from a local file**, checksum checked.
- [ ] **T9.10 F-Droid** metadata and submission (user approval for any
  external submission).

## M6 and later

See `PLAN.md` section 4.
