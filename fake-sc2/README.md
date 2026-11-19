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

Piloting commands (TakeOff, UserTakeOff, Landing, Emergency,
NavigateHome, Circle, PCMD) drive a flight simulation of the Disco with its
firmware's values: take-off by throw, climb to the circling altitude and
circle (30 m radius, 12 m/s), manual flight with the sticks (`sticks
<roll> <pitch> <gaz>` for the controller's, or the app's PCMD), the loiter
button, a return home that flies over home and circles it (also after
`return_home_delay` without the controller, and on a critical battery), a
circular landing on a 10 degree slope, the geofence (minimum and maximum
altitude, maximum distance), battery drain, and the flight plan's playing
state. See the comment above `flight_tick` and the control socket's
commands in `src/fake_sc2.c`.

The Disco's other settings and states live in `src/disco.c`, modelled on
the Disco firmware 1.7.1 (the commands its flight program decodes and the
events it generates, with its default configuration): identity, geofence
and circling settings, return home settings, camera settings and
orientation, video recording and pictures with the internal storage
counters, magnetometer and pitot calibrations, Wi-Fi settings, scan and
authorised channels, battery alerts, sensors, flight meter, run id, flight
plan states. Commands the real flight program ignores get no answer.
`disco` on the control socket prints them; `alert <name|auto>` forces an
alert.

The controller's own model lives in `src/sc2.c`: its input mapper (the
mappings FreeFlight's control mapping screen shows and changes, grabbed
inputs, mapped actions: the left slider tilts the Disco's camera), access
point and co-piloting settings. `button <name>` and `axis <name> <value>`
on the control socket are the physical inputs; `sc2` prints the state.
`fail <name> [on|off]` injects the Disco's failures (motor, magnetometer,
pitot, sensors, camera, storage, weak link, GPS, video, motor cut-out);
`unhandled` lists the commands nothing in the fake handles.

Protocol tests: `make fake-test` runs each `tests/fake/*.probe` script
with `disco-probe` (`src/probe.c`, a scripted ARSDK controller that
connects over mux like the phone) against a fresh fake, without the
emulator. A script sends commands by name and expects the fake's answers
(see the comment at the top of `src/probe.c`).

Faults for robustness tests (SkyController mode): `drone_link lost|ok`
(the Disco leaves or comes back in the controller's range) and
`unplug <seconds>` (the phone's session is closed and refused for a while,
like pulling the USB cable).

Controller buttons: the app grabs buttons through the Mapper feature;
`button <name>` (settings, rth, takeoff, b, a, rear_left, rear_right)
presses and releases a grabbed one. `circle` puts the Disco straight into
its circle around home, with heading, bank and position moving.

The controller's own buttons: `button takeoff` (take-off when landed, else
landing) and `button rth` (return home on or off) act on the Disco when the
app hasn't grabbed them, as on the real SkyController 2, which is how
FreeFlight takes off a Disco. Over the emulated USB link (`make emu-usb`),
FreeFlight Pro talks to this fake as to a real controller
(`tests/scenarios/freeflight-usb.yaml`).
