# disco-flying-wing-fpv

Open-source Android app for the Parrot Disco flying wing flown with a
SkyController 2: live video in a side-by-side headset view (FPV) with a
HUD, on modern Android phones. Built on the community
[Unified GroundSdk](https://github.com/UnifiedGroundSdk/groundsdk-manifest)
fork of Parrot's GroundSDK, and developed against a fake SkyController 2 +
Disco so it can be tested entirely in software.

Not affiliated with or endorsed by Parrot. "Parrot", "Disco",
"SkyController" and "FreeFlight" are trademarks of Parrot Drones SAS.

## Licence

BSD-3-Clause, see `LICENSE` and `NOTICE`. The app uses Parrot's Ground SDK, distributed
under the PARROT GROUND SDK End User License Agreement (`EULA.md` in the
SDK manifest), whose open-source components are BSD-3-Clause.

Portions copyright (c) 2019–Present PARROT DRONES SAS. All rights reserved.

## Status

Working in the test rig (not yet tried on the real hardware):

- Connection to the SkyController 2 and the Disco, flight view with live
  video and a HUD (flying state, altitude, air and ground speed, drone
  and controller battery, GPS).
- Headset view: side-by-side, lens-corrected video with the HUD above and
  below the picture in each eye; lens offsets and height set from a
  calibration panel (tap the view) and saved.
- Robustness: Disco out of range (LINK LOST, video cleared, everything
  back by itself), controller unplugged and plugged back, app in the
  background, rotation; low battery shown as a warning.
- HUD modes cycled with the controller's SETTINGS button: classic text,
  minimal (battery and an arrow pointing home), fighter (artificial
  horizon, speed and altitude tapes, heading with a home marker).

## Building and testing

Everything runs in Docker (Linux host with KVM for the emulator).
`make help` lists the targets. The Ground SDK fork is checked out into
`sdk/` by `make sdk-sync`, at the exact revisions recorded in
`sdk.lock.xml`, with the fixes in `patches/` applied; building it asks
you to accept Parrot's Ground SDK EULA once (`make sdk-eula`).

    make sdk-sync sdk-native sdk-aar   # the SDK, as AARs (long, once)
    make check                         # app, unit tests, fakes, emulator, scenarios

`make check` builds the app (`out/disco-fpv-debug.apk`), runs the JVM unit
tests and the fake Disco's protocol tests (`make fake-test`: its settings,
states and flight, modelled on the Disco's firmware, `NOTES.md` section
22), starts the fake SkyController 2 with a Disco (`fake-sc2/`), the
fake camera and a headless Android emulator, and plays the end-to-end
scenarios in `tests/scenarios/` (TypeScript runner in `tests/e2e/`). Its
exit code is the verdict; screenshots and results go to `out/latest/`.

The app: flight view and side-by-side headset view with a HUD in four
modes (classic, minimal, jet fighter, moving map over map tiles), the
controller's buttons to cycle them and bring up the map, warnings from the
Disco's alarms, the last GPS position kept after a loss, the camera tilt,
and a settings screen.

`make check-usb` runs the same scenarios through Android's real USB
accessory path: the emulated phone is put in accessory mode on the
emulator's dummy USB controller and `aoa-host` plays the SkyController's
USB side (`NOTES.md` section 20).

`make sim` (with `make fake`, instead of `make video`) flies a simulated
Disco: ArduPilot SITL for the flight, FlightGear for the camera (on the
host GPU), a TypeScript bridge in `sim/bridge` (`NOTES.md` section 21).
`make sim SIM_ENGINE=gazebo` uses Gazebo instead: its physics and a 3D
world of houses and trees (`sim/gazebo`), and `make sim-view` shows the
Disco from the pilot's position on the PC's screen, as when flying line of
sight (`VIEW=chase` follows it instead; `NOTES.md` section 25).

The fake Disco was also checked against Parrot's own FreeFlight Pro
(`make freeflight FREEFLIGHT_APK=<your copy>`, see `fake-sc2/README.md`).

See also:

- `PLAN.md`: architecture, milestones, test rig, repositories
- `TASKS.md`: what is done and what is next
- `NOTES.md`: research notes and sources
