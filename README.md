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

## Building and testing

Everything runs in Docker (Linux host with KVM for the emulator).
`make help` lists the targets. The Ground SDK fork is checked out into
`sdk/` by `make sdk-sync`, at the exact revisions recorded in
`sdk.lock.xml`, with the fixes in `patches/` applied; building it asks
you to accept Parrot's Ground SDK EULA once (`make sdk-eula`).

    make sdk-sync sdk-native sdk-aar   # the SDK, as AARs (long, once)
    make check                         # app, unit tests, fakes, emulator, scenarios

`make check` builds the app (`out/disco-fpv-debug.apk`), runs the JVM unit
tests, starts the fake SkyController 2 with a Disco (`fake-sc2/`), the
fake camera and a headless Android emulator, and plays the end-to-end
scenarios in `tests/scenarios/` (TypeScript runner in `tests/e2e/`). Its
exit code is the verdict; screenshots and results go to `out/latest/`.

The fake Disco was also checked against Parrot's own FreeFlight Pro
(`make freeflight FREEFLIGHT_APK=<your copy>`, see `fake-sc2/README.md`).

See also:

- `PLAN.md`: architecture, milestones, test rig, repositories
- `TASKS.md`: what is done and what is next
- `NOTES.md`: research notes and sources
