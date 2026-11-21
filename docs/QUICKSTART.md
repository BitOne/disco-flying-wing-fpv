# Quick start

Everything runs in Docker on a Linux PC with KVM (the Android emulator).
`make help` lists every target. This page shows how the parts fit
together, then recipes for the usual setups.

## The parts

```
 your app (emulator or phone)
     |  USB accessory (real path)      or  debug TCP bridge (debug builds)
     v
 fake SkyController 2 + Disco  (make fake, rig/fake-sc2/)  <-- control socket 127.0.0.1:4322
     ^ video (RTP)                      ^ telemetry, flying state, crashes
     |                                  |
 fake camera (make video)          simulator bridge (make sim, rig/sim/bridge/)
 or the simulator's camera              ^ MAVLink
                                   ArduPlane SITL + FlightGear or Gazebo
```

- **fake-sc2**: plays the SkyController 2 and the Disco on the ARSDK
  protocol, as the app sees them (mux over TCP port 4321). Without the
  simulator it flies a simple flight model of its own. Its control socket
  (`printf 'state\n' | nc -q1 127.0.0.1 4322`) drives it: `battery 30`,
  `gps <lat> <lon> <alt>`, `fail gps`, `drone_link lost`, `button takeoff`,
  `sticks <roll> <pitch> <gaz>`, `rc connect sbus` (a hobby radio's
  receiver), `disco` and `state` for its status. See `rig/fake-sc2/README.md`.
- **fake camera** (`make video`): a moving test pattern with a clock.
  `VIDEO_ENV="-e CLOCK_CODE=1"` draws the wall clock as a code for latency
  measurements (`make latency`).
- **simulator** (`make sim`): ArduPlane SITL flying a flying wing, its
  camera from FlightGear (`SIM_ENGINE=flightgear`, the default) or Gazebo
  (`SIM_ENGINE=gazebo`, real 3D objects, camera tilt and pan, crashes),
  and the bridge feeding the fake from it. It replaces the fake camera.
- **emulator** (`make emu`): a headless Android 15 phone. `make emu-usb`
  instead plugs the fake controller into it as a real USB accessory.
- **e2e scenarios** (`tests/scenarios/*.yaml`, `make e2e`): drive the app
  on the emulator against the fake, with screenshots in `out/latest/`.

## Once

```sh
make sdk-sync          # the Ground SDK fork, with our patches
make sdk-eula          # read and accept Parrot's EULA (interactive)
make sdk-native sdk-aar
make sim-image         # only for the simulator (SITL, FlightGear, Gazebo)
```

## The app on the emulator, with the fakes

```sh
make fake video emu    # controller + Disco, camera, emulator
make app app-install   # debug build: reaches the fake by the debug bridge
make e2e               # all app scenarios; or SCENARIOS=tests/scenarios/app-map.yaml
make check             # everything, from the build to the scenarios
```

The real USB path instead (as on a phone plugged into the controller):
`make check-usb`, or `make emu-usb app-usb app-usb-install`.

## The app on the emulator, flying the simulator

```sh
make fake emu app app-install
make sim SIM_ENGINE=gazebo   # SITL + Gazebo + bridge; takes off by itself
make sim-view                # optional: the Disco seen from the pilot's spot, on the PC
```

Open the flight view or the headset view in the app. To fly:

- The controller's sticks: `printf 'sticks 0 50 0\n' | nc -q1 127.0.0.1 4322`
  (roll, pitch, throttle, -100 to 100; pitch forward dives). Centred
  sticks: it circles (loiter).
- Buttons: `button takeoff`, `button rth` (return home), `axis left_slider -50`
  (camera tilt).
- A crash: `make sim-crash` (nose in: motor cut out, then the link lost),
  or dive with the sticks (it slides to a stop: motor cut out, landed).
- `make sim-stop` stops the simulator and resets the fake (flight model,
  crash flags). `make stop` stops everything.

`make sim-world` regenerates the Gazebo world after changing
`rig/sim/bridge/src/gazebo-world.ts`.

## The app on your own phone

### By USB, the real path (release or usb build)

The PC plays the SkyController's USB side (`aoa-host-linux`), so the app
takes exactly the path it takes on the field. Not yet tried with a real
phone: tell us how it goes.

```sh
make fake            # and make video, or make sim for the simulator
make aoa-host-linux
lsusb                # find your phone: e.g. "ID 18d1:4ee7 Google Pixel"
adb install out/disco-fpv-0.1.1.apk   # or a GitHub release; or out/disco-fpv-usb.apk
sudo out/aoa-host-linux --phone 18d1:4ee7 --host 127.0.0.1
```

The phone asks "Open Disco FPV to handle Fake SkyController 2?": OK
(tick "always" to skip it next time). Then the app connects as to a real
controller. Notes:

- `sudo`: aoa-host needs the USB device files; a udev rule giving your user
  access to the phone works too.
- `--phone` names the only device aoa-host touches. Once in accessory mode
  the phone shows up as `18d1:2d00` or `18d1:2d01`, and adb over USB may
  stop until you unplug it; use `adb tcpip` first if you need adb meanwhile.
- If the app was opened before the phone was plugged, Android asks for the
  USB permission instead: allow it.

### Over Wi-Fi, with the debug build

The debug build can reach the fake over the network (the SDK's debug
bridge), with the phone and the PC on the same network:

```sh
make fake FAKE_ARGS="--mux-bind 0.0.0.0"   # TCP 4321 on all interfaces
make app DEBUG_BRIDGE=192.168.1.20  # your PC's address on that network
adb install -r out/disco-fpv-debug.apk
```

By default the fake listens on this PC only (127.0.0.1): whoever connects
to it takes the session over, so open it to the network only for this, on
a network you trust, and let TCP 4321 through the PC's firewall for the
phone's address only. Open the app: it connects to the
fake controller over Wi-Fi, video included (the mux carries it). Rebuild
with plain `make app` for the emulator (10.0.2.2).

## Measurements and other tools

- `make latency LATENCY_PKG=io.github.bitone.discofpv`: glass-to-glass
  video latency, CPU and memory, with the clock-coded camera
  (`NOTES.md` section 28).
- `make map-test`: renders offline map tiles from a Mapsforge file.
- `make fake-test`: the fake's protocol tests, no emulator.
- `make freeflight FREEFLIGHT_APK=<your copy>`: Parrot's FreeFlight Pro
  against the fake (not redistributed).
- `make sim-unit`, `make e2e-unit`, `make unit`: unit tests.

## A release

```sh
make release-key   # once: the signing key in ~/.config/disco-fpv-release (back it up)
make release       # out/disco-fpv-<version>.apk, signed
```

Every update must be signed with the same key: keep a copy of
`~/.config/disco-fpv-release` somewhere safe.
