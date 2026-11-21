# disco-flying-wing-fpv

<img src="docs/logo/icon.svg" alt="Disco FPV icon: the flying wing over a HUD horizon" width="96" align="right">

Open-source Android app for the Parrot Disco flying wing flown with a
SkyController 2: live video in a side-by-side headset view (FPV) with a
HUD, on modern Android phones. Built on the community
[Unified GroundSdk](https://github.com/UnifiedGroundSdk/groundsdk-manifest)
fork of Parrot's GroundSDK.

Not affiliated with or endorsed by Parrot. "Parrot", "Disco",
"SkyController" and "FreeFlight" are trademarks of Parrot Drones SAS.

## Installing

Download the APK from the
[releases](https://github.com/BitOne/disco-flying-wing-fpv/releases) and
install it on the phone (Android 7 or newer; allow installing from that
source when asked). Plug the phone into the SkyController 2 by USB and
open the app. Nothing is installed on the Disco or the controller.

Not yet tried on the real hardware: every feature below works against
the fake SkyController 2 and Disco of the test rig, and the simulator.

## What it does

- Connection to the SkyController 2 and the Disco, flight view with live
  video and a HUD (flying state, altitude, air and ground speed, drone
  and controller battery, GPS).
- Headset view: side-by-side, lens-corrected video with the HUD above and
  below the picture in each eye; lens offsets and height set from a
  calibration panel (tap the view) and saved; head tracking for the
  camera.
- HUD modes cycled with the controller's buttons: classic text, minimal
  (battery and an arrow pointing home), jet fighter (artificial horizon,
  speed and altitude tapes, heading with a home marker), moving map.
- Moving map over map tiles: aerial photos (IGN, France), satellite
  photos worldwide, any XYZ tile URL, or an offline OpenStreetMap file
  from download.mapsforge.org. The map screen previews it and downloads
  the area around the Disco's last position, the phone's position or
  typed coordinates, for flying without network.
- Warnings from the Disco's alarms, the last GPS position kept after a
  loss, the camera tilt.
- Robustness: Disco out of range (LINK LOST, video cleared, everything
  back by itself), controller unplugged and plugged back, app in the
  background, rotation.
- The Disco's settings: flying zone (maximum altitude and distance),
  return home (delay after a link loss, where to), circling altitude and
  direction, Wi-Fi country and outdoor rules (they set the radio's
  channels and transmit power limits: choose what your local rules
  allow), firmware update from Parrot's own files.
- Flight plans, free: waypoints tapped on the map with their height,
  uploaded and flown, paused and resumed.
- Every flight recorded on the phone, with the total flight time, and
  exported as GPX, KML or CSV.
- Photos and videos on the Disco: listed with thumbnails, downloaded to
  the phone's gallery, deleted.
- HUD extras: the Wi-Fi signal, recording time or free storage, the
  wind, and the time and battery needed to get home (GO HOME warning).
- No account and no data sent anywhere: GroundSdk's collectors for
  Parrot's cloud are off; the only Internet requests are the map tiles,
  GPS ephemeris for the Disco, and Parrot's firmware list (which
  versions exist; GroundSdk asks now and then when online).

## Licence

BSD-3-Clause, see `LICENSE` and `NOTICE`. The app uses Parrot's Ground
SDK, distributed under the PARROT GROUND SDK End User License Agreement
(`EULA.md` in the SDK manifest), whose open-source components are
BSD-3-Clause.

Portions copyright (c) 2019–Present PARROT DRONES SAS. All rights reserved.

## The repository

| Directory | What |
|---|---|
| `app/` | the Android app (Kotlin) |
| `rig/` | the test rig: fake SkyController 2 + Disco, fake camera, Android emulator with the emulated USB link, simulated Disco; see [rig/README.md](rig/README.md) |
| `tests/` | the end-to-end runner (TypeScript) and its scenarios: the app on the emulator against the rig |
| `docs/` | [quick start](docs/QUICKSTART.md), logo, screenshots |
| `sdk.lock.xml`, `patches/` | the Ground SDK fork's pinned revisions and our fixes to it (shared by the app and the fake) |

## Building and testing

Everything runs in Docker (Linux host with KVM for the emulator).
[docs/QUICKSTART.md](docs/QUICKSTART.md) has the recipes: the app with the
fakes, flying the simulator, your own phone by USB or Wi-Fi, a release.
`make help` lists the targets, the app's and the SDK's from `Makefile`,
the rig's from `rig/rig.mk`. The Ground SDK fork is checked out into
`sdk/` by `make sdk-sync`, at the exact revisions recorded in
`sdk.lock.xml`, with the fixes in `patches/` applied; building it asks you
to accept Parrot's Ground SDK EULA once (`make sdk-eula`).

    make sdk-sync sdk-native sdk-aar   # the SDK, as AARs (long, once)
    make check                         # app, unit tests, fakes, emulator, scenarios
    make stop                          # stop the simulator, camera, fake and emulator

`make check` builds the app (`out/disco-fpv-debug.apk`), runs its JVM unit
tests and the fake Disco's protocol tests, starts the rig (fake
SkyController 2 with a Disco, fake camera, headless Android emulator) and
plays the end-to-end scenarios in `tests/scenarios/`. Its exit code is the
verdict; screenshots and results go to `out/latest/`. `make check-usb`
plays them through Android's real USB accessory path instead of the debug
build's network bridge.

See also:

- `PLAN.md`: architecture, milestones, test rig
- `TASKS.md`: what is done and what is next
- `NOTES.md`: research notes and sources
