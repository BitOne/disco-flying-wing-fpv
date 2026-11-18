# fake-sc2

Fake Parrot SkyController 2 with a connected Disco, for end-to-end tests of
the app on the Android emulator. It speaks the SkyController's USB protocol
("mux") over TCP, which the SDK's debug USB bridge connects to.

Adapted from Parrot's `arsdk-ng/examples/device.c` (BSD-3-Clause,
Copyright (c) 2019 Parrot Drones SAS); `src/fake_sc2.c` keeps its original
licence header. Built with Alchemy through the SDK's `olympe-linux` product:

    make fake-build   # build (Linux x86_64)
    make fake         # start in the background (container disco-fpv-fake)
    make fake-stop

`host-libs/` declares system libraries the SDK's Linux product doesn't.

`--disco` runs a bare Disco over Wi-Fi instead (ARSDK network backend on
TCP 44444, ARStream2 video to the client's port), used to validate the
fake against Parrot's FreeFlight Pro inside the emulator:

    make fake-android                          # NDK build for the x86_64 emulator
    make freeflight FREEFLIGHT_APK=<your copy> # fake in the emulator + mDNS + FreeFlight
    make e2e SCENARIOS=tests/scenarios/freeflight-*.yaml E2E_CTRL_PORT=4422
    make freeflight-stop

`app/fakedisco` is the helper app announcing it over mDNS (Android NSD).

Piloting commands (TakeOff, UserTakeOff, Landing, Emergency, NavigateHome)
drive a small flight simulation of the Disco's sequences (take-off by
throw, climb to 50 m and circle, landing, motor cut, return home); see the
comment above `flight_tick` and the control socket's `state` and `throw`
commands in `src/fake_sc2.c`.

Faults for robustness tests (SkyController mode): `drone_link lost|ok`
(the Disco leaves or comes back in the controller's range) and
`unplug <seconds>` (the phone's session is closed and refused for a while,
like pulling the USB cable).

Controller buttons: the app grabs buttons through the Mapper feature;
`button <name>` (settings, rth, takeoff, b, a, rear_left, rear_right)
presses and releases a grabbed one. `circle` puts the Disco straight into
its circle around home, with heading, bank and position moving.
