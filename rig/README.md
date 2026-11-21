# The test rig

Everything that stands in for the real hardware, so the app (`../app`) is
built and tested entirely in software: a fake SkyController 2 with a
Disco, a fake camera, an Android emulator, and a simulated Disco flying in
a 3D world. Its targets are in `rig.mk`, included by the top `Makefile`:
run them from the repository root (`make help` lists them). It also works
with apps other than ours: Parrot's FreeFlight Pro was flown against it.

| Directory | What | Make targets |
|---|---|---|
| `fake-sc2/` | fake SkyController 2 + Disco (C, adapted from Parrot's `arsdk-ng/examples/device.c`), with a control socket for the tests; see [fake-sc2/README.md](fake-sc2/README.md) | `fake-build`, `fake`, `fake-stop`, `fake-test`, `fake-android` |
| `tests/fake/` | protocol tests of the fake Disco (`disco-probe` scripts) | `fake-test` |
| `docker/video-src/` | fake camera: GStreamer test pattern (or a clock code for latency) as RTP H.264 | `video`, `video-stop` |
| `docker/emulator/` | headless Android 15 emulator (KVM) | `emu`, `emu-stop`, `emu-screenshot` |
| `aoa-host/` | the SkyController's USB side of Android's accessory protocol, in the emulator or on this PC for a real phone by USB | `aoa-host`, `aoa-host-linux`, `emu-usb` |
| `sim/`, `docker/sitl`, `docker/flightgear`, `docker/gazebo`, `docker/cesium` | simulated Disco: ArduPilot SITL for the flight, FlightGear or Gazebo for the camera, a TypeScript bridge (`sim/bridge`) between SITL and the fake | `sim-image`, `sim`, `sim-view`, `sim-crash`, `sim-stop`, `sim-unit`, `sim-world` |
| `tests/sim/` | probe script for the simulated Disco | |
| `fakedisco/` | helper app announcing the fake Disco over mDNS, for FreeFlight Pro inside the emulator | `freeflight-helper`, `freeflight`, `freeflight-stop` |
| `scripts/` | emulator start and USB setup, fake tests, FreeFlight setup, a simulator frame grab | |

The fake and the app are built from the same pinned Ground SDK fork
(`../sdk.lock.xml`, `../patches/`), so the protocol the fake answers is the
one the app speaks. The end-to-end runner and scenarios that play the app
against the rig are in `../tests/`.

## Running it

    make fake video emu              # the fakes and the emulator
    make app app-install             # our app (debug build, network bridge to the fake)
    make e2e                         # the app's scenarios
    make sim                         # a simulated Disco instead of the test pattern (after make fake)
    make sim SIM_ENGINE=gazebo; make sim-view   # Gazebo, and the line-of-sight view
    make emu-usb                     # the emulator with the real USB accessory path
    make stop                        # everything down

The fake Disco's settings, states and flight are modelled on the Disco's
firmware (`../NOTES.md` section 22); the USB path is in section 20, the
simulator in sections 21 and 25. [../docs/QUICKSTART.md](../docs/QUICKSTART.md)
has the recipes, including your own phone against the fakes by USB or
Wi-Fi.
