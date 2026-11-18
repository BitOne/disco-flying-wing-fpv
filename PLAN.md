# Plan: agent-driven build of an open-source FreeFlight FPV app

Goal: an open-source Android app for the Parrot Disco + SkyController 2 with
a side-by-side headset view, built by Claude in a loop and tested entirely in
software (fake drone + Android emulator), with a few human checkpoints on
real hardware.

Background research: `NOTES.md` sections 10 to 14.

## 1. Key findings that make this possible

- **The SDK can speak the controller's USB protocol over TCP.** The fork's
  `sdkcore` has `ArsdkDebugMuxBackendController`: the same "mux" protocol the
  SkyController 2 uses over USB, but over a TCP socket to a configured
  `host:port` (resources `arsdk_debug_usb_bridge_address`,
  `arsdk_debug_usb_bridge_port`, default 13579), enabled by
  `gsdk_usb_debug_enabled` (already `true` in the demo app). So the app in an
  emulator follows the same code path as with the real controller, except
  the Android USB accessory layer at the very bottom.
- **Parrot's `arsdk-ng` ships a fake drone** (`examples/device.c`) that can
  already listen for mux over TCP (`mux_new` on an accepted socket), as well
  as the Wi-Fi protocol (TCP 44444 handshake). We use it as the **reference**
  for our fake, not as its code base: new software is written in
  **TypeScript** (Node). The fake ports the needed protocol layers from the C
  references: mux framing and channels (`libmux`, ~5,000 lines of C including
  the IP proxy we don't need), ARSDK transport and handshake (`arsdk-ng`
  `libarsdk`, ~5,300 lines), and command encoding generated from the
  `arsdk-xml` definitions. The C example stays useful as a conformance
  check: our TypeScript fake and Parrot's C controller example must
  interoperate.
- **The fork builds x86_64 native libraries** (`buildcfg.py`:
  `armeabi-v7a`, `arm64-v8a`, `x86_64`), so the app runs on a fast x86_64
  emulator image.
- **Host:** KVM usable by the user, Docker available, 8 cores, 30 GB RAM,
  116 GB free disk. Android SDK not installed yet (will live in a container
  or a project-local directory).

## 2. Architecture of the test rig

```
┌──────────────── host (or one Docker compose stack) ────────────────┐
│                                                                     │
│  fake-sc2 (TypeScript, Node)              scenario control (HTTP)   │
│   ├ mux over TCP :13579  ◄──────────┐      ▲  set battery, GPS,     │
│   ├ presents a SkyController 2      │      │  link loss, attitude…  │
│   ├ drone manager: one Disco,       │      │                        │
│   │  "connected"                    │   test runner (pytest)        │
│   ├ Disco state events (telemetry)  │      │  drives scenarios,     │
│   └ RTP H.264 on the mux RTP        │      │  collects screenshots, │
│     channel  ◄── GStreamer          │      │  logcat, logs          │
│        (test pattern with           │      ▼                        │
│         frame counter + clock)      │   adb ──► Android emulator    │
│                                     │          x86_64, headless,    │
│  optional: ArduPilot SITL ─► bridge │          KVM, swiftshader GPU │
│  (telemetry), Gazebo camera (video) └───────── app (debug build)    │
│                                                 connects to         │
│                                                 10.0.2.2:13579      │
└─────────────────────────────────────────────────────────────────────┘
```

- The emulator reaches the host at `10.0.2.2`, and the app connects out to
  the fake controller over TCP, so no special emulator networking is needed
  for the mux path (UDP/Wi-Fi path would need port redirects; not needed).
- Fake drone behaviour is driven by **scenario files** (YAML: timeline of
  states) and a small **control API**, so tests can say "battery 15% at
  t=10 s, GPS lost at t=20 s, link drop at t=30 s".
- Video test pattern burns in a frame counter and timestamp (GStreamer
  `timeoverlay`/`textoverlay`) plus a fixed marker grid, so screenshots can
  be checked automatically (pattern present in both eyes, frames advancing,
  rough latency).

## 3. Repository layout

```
disco-flying-wing-fpv/
├── NOTES.md, PLAN.md, TASKS.md      research, this plan, live backlog
├── sdk/                             repo checkout of Unified GroundSdk (not committed; script)
├── app/                             our Android app (Gradle, Kotlin), depends on the SDK AARs
├── fake-sc2/                        TypeScript fake SkyController 2 + Disco (mux, ARSDK), Dockerfile
├── sim/                             optional SITL bridge (TypeScript), Gazebo camera, GStreamer pipelines
├── tests/
│   ├── scenarios/*.yaml             fake drone timelines
│   ├── e2e/                         pytest: emulator + fake + screenshot/log checks
│   └── golden/                      reference screenshots (with tolerances)
├── docker/                          build images: sdk-build, android-sdk+emulator
└── Makefile                         single entry points (below)
```

Entry points the loop relies on (each prints a short summary and writes
artifacts to `out/<timestamp>/`):

- `make sdk` builds the fork (native + AARs) in Docker; cached.
- `make app` builds the debug APK; `make unit` runs JVM unit tests.
- `make fake` builds and starts the fake controller.
- `make emu` starts a headless emulator (snapshot boot), waits for it.
- `make e2e` installs the app, runs scenarios, collects screenshots,
  logcat, fake-drone logs, and fails on any check.
- `make check` = `app unit fake emu e2e`, exit code is the verdict.

## 4. Milestones and acceptance criteria

Each milestone ends green on `make check` and is committed.

**M0. Toolchain** (most fragile)
- Docker image building the fork with `repo`, Alchemy, NDK r21, JDK 11.
- Android SDK + x86_64 system image + emulator in a container or local dir.
- Done when: `groundsdkdemo` APK builds, installs on the headless emulator,
  and a screenshot shows its device list.

**M1. Fake controller connects**
- `fake-sc2` (TypeScript) implements mux framing, the ARSDK transport and
  handshake, and command codecs generated from `arsdk-xml`, with unit tests
  against byte captures from the C reference (Parrot's `arsdk-ng` example
  controller and device, built in Docker).
- It presents a SkyController 2 over mux/TCP, with a Disco in its drone
  manager, connected.
- Done when: the demo app (debug bridge pointed at `10.0.2.2:13579`) shows
  the SkyController 2 and the Disco as connected, and logcat shows the
  handshake. Disco telemetry events (battery, altitude, GPS) appear in the
  demo's info screens.

**M2. Video**
- GStreamer test pattern sent as RTP over the mux RTP channel.
- Done when: the demo app's HMD activity shows the pattern in both eyes
  (automated screenshot check), and frame counters advance across two
  screenshots.

**Human checkpoint A (real hardware, ~1 evening)**
- Install the same demo APK on the Fairphone, plug into the real
  SkyController 2 with the real Disco on the bench (propeller off).
- Confirm connection, telemetry, live video, HMD view.
- Record the session: GroundSDK command/event logs from logcat. These
  become the reference the fake must reproduce (fidelity). Mismatches become
  tasks.

**M3. Our app skeleton**
- New app module using `groundsdk`/`arsdkengine`/`sdkcore`.
- Screens: connection status, normal video view with 2D HUD.
- Done when: e2e scenarios check that HUD values match the scenario
  (altitude, speed, airspeed, battery, GPS), on screenshots and through
  UIAutomator text/content-descriptions.

**M4. Headset view**
- `GsdkHmdView` full screen with HUD overlay, lens offsets saved, entry and
  exit gestures, keep screen on, immersive mode.
- Done when: golden-image checks pass (left/right symmetric content, overlay
  present), lens settings persist across restarts.

**M5. Robustness scenarios**
- Low battery, GPS loss, controller-drone link loss and recovery, controller
  unplug (TCP close) and reconnect, app background/foreground, rotation.
- Done when: each scenario has an e2e test with expected UI states and no
  crash or ANR in logcat.

**M6. Realistic flight data (optional)**
- ArduPilot SITL plane (flying wing) + MAVLink-to-ARSDK bridge driving
  telemetry; optionally Gazebo camera video.
- Done when: a scripted flight runs end to end and HUD values track SITL.

**M7. Release**
- targetSdk 34/35, current AGP, 16 KB page alignment, GitHub Actions
  running `make check` on an emulator, signed release APK, README.

**Human checkpoint B (real hardware)**
- Headset comfort and lens tuning with the actual headset, then a careful
  first flight with FreeFlight Pro installed as a fallback.

## 5. How the agent loop runs

- **Backlog in `TASKS.md`:** ordered tasks, each with a done-criterion that
  maps to a `make` target or a test. The loop always takes the first open
  task.
- **One iteration:**
  1. read `TASKS.md`, `NOTES.md`, last run summary;
  2. implement the task (small, focused change);
  3. run `make check` (or the narrower target for infra tasks);
  4. read the artifacts: test report, logcat excerpts, screenshots (Claude
     can look at the PNGs directly);
  5. if green: commit on a feature branch, tick the task, add follow-ups;
     if red: fix and retry, at most N attempts, then write a short
     "blocked" note in `TASKS.md` and move on or stop.
- **Running it:** `/loop` in Claude Code with a self-paced prompt such as
  "work the next task in TASKS.md following PLAN.md section 5", or headless
  `claude -p` invoked from a shell loop. Long builds run in the background
  and wake the loop when they finish.
- **Guardrails:**
  - everything that downloads or builds third-party code runs in Docker;
  - commits only on a branch, never pushes without asking;
  - no changes outside the project directory;
  - stop and ask when a task needs a human decision or real hardware
    (checkpoints A and B), or after repeated failures.
- **Memory:** `NOTES.md` for research facts, `TASKS.md` for state,
  `out/latest/summary.md` for the last run, so a fresh session can resume.

## 6. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Old native toolchain (repo, Alchemy, NDK r21) won't build cleanly | Pin everything in a Docker image; M0 first; ask the fork author if stuck |
| Fake controller doesn't match the real SkyController 2 (drone manager states, stream setup) | Checkpoint A recording as reference; keep fake behaviour data-driven |
| TypeScript port of mux/ARSDK has subtle framing bugs | Byte-level tests against the C reference implementations; interop test with Parrot's C example controller |
| Mux-over-TCP skips the Android USB accessory layer | Covered by checkpoint A and B on real hardware only |
| Emulator GPU (swiftshader) differs from the phone's GPU for the HMD shaders | Checkpoint A visual check; keep shaders GLES 2 |
| Screenshot tests are flaky | Marker-based checks with tolerances rather than exact pixels; retries |
| Fork bugs in Disco support | Fix in our fork of the fork, upstream to UnifiedGroundSdk |
| Loop drifts or burns tokens on a stuck task | Attempt limit per task, blocked notes, human review at each milestone |

## 7. GitHub repositories (account `BitOne`)

1. **`BitOne/disco-flying-wing-fpv`**: the main monorepo. App, fake
   controller, tests, Docker images, Makefile, docs (`NOTES.md`, `PLAN.md`,
   `TASKS.md`). Kept together because `make check` builds and tests them as
   one unit and they change together. Licence: BSD-3-Clause, same family as
   the Parrot SDK it builds on.
2. **`BitOne/groundsdk-manifest`**: fork of
   `UnifiedGroundSdk/groundsdk-manifest`, created only when we first need to
   patch an SDK repo. It pins exact revisions and points the patched
   projects at our forks; `make sdk` does
   `repo init -u https://github.com/BitOne/groundsdk-manifest`. Until then we
   use the upstream manifest pinned to a revision.
3. **Forks of individual SDK repos, on demand** (most likely
   `groundsdk-android`, maybe `arsdk-ng`, `groundsdk-product`), each with a
   branch per fix, and pull requests back to `UnifiedGroundSdk` so fixes
   don't stay with us.
4. **Later, maybe `BitOne/parrot-fake-controller`**: if the TypeScript fake
   turns out useful beyond the Disco (Bebop, other apps), extract it from
   the monorepo with its history (`git subtree split`). Not before it's
   stable.

Not reused: the earlier `BitOne/parrot-disco-drone-emulator` (obsolete
test).

## 8. First concrete steps

1. Create `TASKS.md` with M0 split into tasks (Docker SDK build image,
   `repo sync`, native build, AAR build, Android SDK + emulator setup,
   demo install + screenshot).
2. Run M0 interactively (not in the loop) because it's the most uncertain.
3. Then start the loop at M1.
