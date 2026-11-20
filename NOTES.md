# Parrot Disco + ArduPilot + Fairphone 5: side-by-side FPV video

Research notes, 2026-11-18.

Goal: fly a Parrot Disco on ArduPilot, with QGroundControl on a Fairphone 5,
and show the video side by side (SBS) in a headset, like the original
FreeFlight app did.

**Update 2026-11-18:** approach changed to "install nothing on the Disco or
the controller" (stock Parrot firmware, FreeFlight Pro or equivalent). See
section 10. Sections 1 to 9 cover the ArduPilot route.

## 1. FPV_VR_OS on the Fairphone 5

Repo: https://github.com/Consti10/FPV_VR_OS

What I found in the build files and on GitHub:

- **Android target:** `targetSdkVersion = 33`, `compileSdkVersion = 33`,
  `minSdkVersion = 21`, ABIs `armeabi-v7a` and `arm64-v8a`. Android 14 and
  later only refuse apps that target below SDK 23, so it should install on the
  FP5's current Android version.
- **Activity:** last push March 2024. Last GitHub release v1.15, June 2020.
  Not abandoned, but not really maintained either; the author moved on to
  OpenHD.
- **Building:** you'll probably have to build it yourself. It needs the
  sibling repos `LiveVideo10ms` and `RenderingX` cloned in the same parent
  folder, an old NDK (`20.0.5594570`) and Android Gradle Plugin 7.3.1. Expect
  some Gradle fixes with a recent Android Studio. A paid Play Store build
  ("FPV-VR") also existed, if it's still listed.
- **Fit:** it does what we want. Low-latency H.264 decoding over UDP (raw or
  RTP), SBS rendering with lens-distortion correction for a headset, and a
  MAVLink on-screen display (OSD), so flight telemetry shows in the headset
  without QGC.

Alternatives: I found no maintained Android app with a headset mode. The
OpenIPC apps (PixelPilot, FPVue) are modern but built around their own video
link. QGroundControl has no SBS mode.

Fallback: a minimal custom app is realistic. Android's hardware decoder
(MediaCodec) renders into a SurfaceTexture, which is drawn twice with a
lens-distortion shader: a few hundred lines.

## 2. Getting video out of the Disco under ArduPilot

- **No built-in video:** ArduPilot doesn't stream video on the Disco, and
  the official Disco page doesn't mention video at all. The Parrot flight
  stack (`dragon`) is not running under ArduPilot.
- **Community workaround** (tridge, ArduPilot lead developer, 2019,
  PR ArduPilot/ardupilot#10829 "Linux: added support for GPIO scripts"): a
  `gpio.sh` script runs when an RC switch toggles a relay, and calls Parrot's
  own tool:
  - `/usr/bin/pimpctl stream-start front $GCS_IP 8888`
  - `/usr/bin/pimpctl stream-stop front $GCS_IP 8888`
  - `pimpctl recording-start front`, `recording-stop front`,
    `take-picture front` (recording goes to the Disco's internal storage).
  - `GCS_IP` is taken from the first connection on UDP 14550:
    `netstat -n|grep 14550 | head -1 | awk '{print $5}'| cut -d: -f1`
  - Parameters (tridge): `RELAY_PIN=100`, `RELAY_PIN2=101`, `RELAY_PIN3=102`,
    `RC6_OPTION=28` (photo), `RC7_OPTION=34` (recording),
    `RC8_OPTION=35` (streaming). Another user used `RELAY_PIN=102`
    (streaming), `RELAY_PIN2=100` (photo), `RELAY_PIN3=101` (video).
  - Prebuilt binary from 2019: http://uav.tridgell.net/Disco/video_control/arduplane
  - I haven't checked whether the PR was merged.
- **Format:** not confirmed for port 8888. Parrot streams were RTP/H.264, so
  that's likely, but check it with `tcpdump` or `ffprobe` on a laptop first.
- One user reported that a Parrot firmware update broke the stream.

## 3. SkyController 2: not a Wi-Fi card for the phone in stock form

From Parrot's open-source SDK code (`libARDiscovery`, `libmux`):

- **Phone to controller:** the phone doesn't get a network interface.
  FreeFlight connects as a USB accessory (Android Open Accessory on Android,
  Apple's accessory framework on iOS) and runs Parrot's "mux" protocol over
  the cable, which carries commands, telemetry and video.
- **Controller to Disco:** the controller joins the Disco's Wi-Fi as a
  client, using its own antennas (2.4 GHz; no 5.8 GHz unlike SkyController 1),
  and keeps a session on port 9988. That part is the long-range link.
- **What that means:** over the phone's USB cable, no IP traffic reaches the
  phone. Only an app built on Parrot's SDK can use that link, so QGC and
  FPV_VR can't. Also, without Parrot's software running on the Disco, the
  stock controller has nothing to talk to.
- **Exception, video over a USB-Ethernet adapter:** the stock controller also
  accepts a USB-Ethernet adapter, gives the computer an address by DHCP, and
  can resend the drone's RTP video to it. See section 8. No telemetry or
  commands go that way, only video.

The controller runs an Android-like Linux with `adb` enabled by default
(`adb connect 192.168.53.1:9050` over the Ethernet adapter).

## 4. dema-rc: SkyController 2 firmware for ArduPilot

Repo: https://github.com/ArduPilot/dema-rc (author Lucas De Marchi; moved
into the ArduPilot GitHub organization in August 2020). Docs:
https://ardupilot.github.io/dema-rc/ . Last commit July 2023, about 22 stars.

From its connection script (`distro/sc2/usr/bin/dema-rc-cm`):

- **Sticks:** read on the controller and sent to ArduPilot as UDP RC input
  (derived from Julien Beraud's `joystick_remote` Bebop protocol), shown as RC
  channels. Roll and pitch need reversing (`RC1_REVERSED=1`,
  `RC2_REVERSED=1`; QGC RC calibration handles it). RC failsafe was reported
  working. Buttons are momentary only, so ArduPilot doesn't parse them well.
- **Reconnect:** keeps the controller's Wi-Fi connected to the Disco
  (`wifid-cli connect "$ssid"`, SSID in `/data/ftp/internal_000/ssid.txt`).
  LED: blinking blue means searching, solid blue means connected.
- **MAVLink:** UDP 14550 arriving from the ground station is forwarded to
  the Disco at 192.168.42.1:
  ```
  iptables -t nat -A PREROUTING -p udp --dport 14550 -j DNAT --to-destination 192.168.42.1
  iptables -t nat -A POSTROUTING -p udp --dport 14550 -j MASQUERADE
  ```
- **Video:** it watches for the ground station's address on `eth0`, then
  forwards UDP **8888** from the Disco to the ground station:
  ```
  iptables -t nat -A PREROUTING -p udp --dport 8888 -j DNAT --to-destination $gcs_ip
  iptables -t nat -A POSTROUTING -p udp --dport 8888 -j MASQUERADE
  ```
  Port 8888 is the one tridge's `pimpctl stream-start` hook sends to. On the
  Disco, the ground station appears to be the controller (masquerade), so the
  stream goes to the controller, which passes it on to the phone. The two
  mods appear designed to work together.
- **Ground station link:** a USB-to-Ethernet adapter in the controller's USB
  port, with the controller at `192.168.53.1`. The author says only USB 2.0,
  10/100 adapters with an ASIX chip worked (gigabit and USB 3.0 didn't, even
  with ASIX). In QGC, add `192.168.53.1` as a target host on 14550.
- **Install:** laptop on the controller's Ethernet adapter, then
  `adb connect 192.168.53.1:9050`, `adb shell`, `install.sh` from
  `distro/skycontroller2`, binaries in `/data/ftp/internal_000/ardupilot/bin/`.
  Reboot the controller, then press "settings" to switch from stock to
  dema-rc.
- **Disco side:** ArduPilot 4.0.7 or later (PR #13607 for UDP RC input).
  ArduPilot started with something like
  `-A udpin:0.0.0.0:14550 ... -C udp:192.168.43.255:14550:bcast`
  (see `docs/install-ardupilot.md`).

It acts as a "long-range network card" in effect, but limited to those two
ports and wired over Ethernet.

## 5. Target setup

```
Fairphone 5 (QGC / FPV_VR_OS)
  └ USB-C Ethernet adapter ── cable ── ASIX USB2 Ethernet adapter
                                         └ SkyController 2 (dema-rc)
                                              ~~ 2.4 GHz Wi-Fi ~~ Disco (ArduPilot + pimpctl stream on 8888)
```

Android supports USB Ethernet adapters natively, so the FP5 should work.
Reported variant with no cable to the phone: a Raspberry Pi or travel router
bridges the controller's Ethernet to Wi-Fi
(phone Wi-Fi <-> Pi Wi-Fi <-> Pi Ethernet <-> controller Ethernet adapter).

### Running QGC and the headset view together

Only one app is in the foreground in the headset, and both apps want MAVLink
on UDP 14550. Options:

- **Forward from QGC:** QGroundControl can forward MAVLink to another
  address, for example `127.0.0.1:14551`. Point FPV_VR's telemetry there.
- **Second output from ArduPilot:** start ArduPilot with an extra UDP output
  on a second port.

Video on 8888 only goes to FPV_VR, which is what we want in the headset.

## 6. Open questions to check

1. **Video through the controller was never confirmed.** The forwarding rule
   exists, but the author wrote in 2023 that he didn't remember whether video
   routing worked, and nobody in the thread reported seeing it.
2. **Phone IP address:** unknown whether the controller hands out addresses
   on its Ethernet side. If not, the phone needs a static `192.168.53.x`
   address, and stock Android doesn't always expose static IP settings for
   Ethernet.
3. **Range:** nobody has published range figures for dema-rc. The radio link
   is the same controller-to-Disco Wi-Fi as stock, so it should be near
   Parrot's stock figure (around 2 km). But with ArduPilot running, Parrot's
   settings for the Disco's access point (channel, transmit power, country)
   may not be applied in the same way. Do a ground range test.
4. **Maintenance:** expect manual installation (adb, cross-compiled binaries)
   and rough edges. Early builds had GLIBC errors; the Disco build needs
   `libiio` and `libserialport`.
5. **Stream format on 8888:** confirm RTP vs raw H.264 before configuring
   FPV_VR_OS.

## 7a. Plan (dema-rc route)

1. Install ArduPilot on the Disco.
2. Bench test with a laptop on the Disco's own Wi-Fi (`DISCO-xxxxxx`,
   192.168.42.x): SSH in, run `pimpctl stream-start front <laptop_ip> 8888`,
   identify the stream with `ffprobe udp://0.0.0.0:8888` or `gst-launch`.
3. Install dema-rc on the SkyController 2, connect the laptop by Ethernet,
   confirm QGC and the sticks work, then trigger streaming and check video
   arrives on 8888 through the controller.
4. Build FPV_VR_OS and point it at port 8888 (RTP or raw H.264, whichever
   matches).
5. Swap the laptop for the Fairphone with a USB-C Ethernet adapter.
6. Range test on the ground before flying.

Fallbacks if the controller path fails: Disco's Wi-Fi straight to the phone
(short range), or 4G with uavpal's disco4g mod (Huawei E3372 modem in the
Disco, ZeroTier tunnel).

## 8. Using the stock SkyController 2 firmware with QGC (research 2026-11-18)

Question: how much work to keep the controller's stock firmware and still get
QGC plus FreeFlight-style video?

### What I checked

- Downloaded the official SkyController 2 firmware 1.0.9
  (`https://firmware.parrot.com/Drones/090f/1.0.9/ALL/mpp_update.plf`,
  MD5 `5d210e5f3221c386867b63a2288dc970`, matches the uavpal wiki) and
  extracted the gzip streams from the PLF.
- Kernel: `Linux version 3.4.11+ ... Tue Oct 30 22:17:37 UTC 2018`. USB
  controller is ChipIdea (host and gadget capable).
- Daemons: `aoad` ("Android Open Accessory manager") handles the phone, and
  `mppd` is the main daemon (ARSDK proxy to the drone, mux to the phone,
  sticks, video).
- Parrot's `libmux` git history: the IP proxy feature (`mux_ip_proxy`, generic
  TCP/UDP tunnelling over the USB link) first appears in the GroundSdk 1.8.0
  release, May 2021. The controller's firmware is from 2018 and its strings
  have no `ip_proxy`, so the stock mux can't tunnel MAVLink. Its mux channels
  are ARSDK only: transport, discovery, backend, stream data, stream control,
  RTP.

### What the stock firmware already does

From `mppd` strings and a Parrot developer forum thread (Parrot employee
"Nicolas", Oct 2016 to Feb 2017):

- **Video restream over Ethernet:** plug a USB-Ethernet adapter (10/100 only,
  ASIX or similar; gigabit and USB 3.0 adapters didn't work; Parrot used
  TrendNet TU2-ET100), the computer gets a `192.168.53.x` address by DHCP
  (`192.168.43.x` before firmware 1.0.4), then open
  `http://192.168.53.1:7711/video` in VLC. `mppd` answers `GET /video` with
  an SDP (`m=video <port> RTP/AVP 96`, `a=rtpmap:96 H264/90000`; Parrot says
  ports 55004/55005 are in the SDP) and starts copying the drone's RTP stream
  to the requester's IP. Quality is the original stream, 480p or 720p.
- Parrot quote: "only the RTP stream is forwarded ... The downside is that you
  can NOT have an ARSDK connection between a PC and a SkyController 2."
- Parrot also confirmed the phone link is "a bulk USB channel between the two
  devices. There is no networking involved at all".
- `mppd` connects to the drone at the hard-coded `192.168.42.1` using the
  ARSDK handshake (TCP 44444, JSON with `c2d_port`, `d2c_port`,
  `arstream2_client_stream_port: 55004`, `arstream2_client_control_port:
  55005`). It rejects unsupported drone models.
- Parrot: "the Drone will only Stream to an ARSDK-connected device". The
  controller sends the start-stream command; the drone then sends RTP
  (ARStream2 is "a simplified implementation of RTP") to 55004.

### What's missing with ArduPilot on the Disco

With ArduPilot the Disco no longer speaks ARSDK, so the stock controller
never connects, never starts the stream, and its sticks go nowhere. And
nothing on the stock controller forwards MAVLink.

### Option S: stock controller + an ARSDK emulator on the Disco

Pieces to build:

1. **Disco side, a small "fake Parrot drone" daemon** (C, cross-compiled for
   the Disco's ARM Linux):
   - Answer the TCP 44444 JSON handshake as a Disco (product `090e`).
   - Speak ARNetwork over UDP (framing, sequence numbers, acks, ping/pong).
   - Send enough state messages for `mppd` to consider the drone connected
     (unknown list: capture a stock Disco session with `tcpdump` and replay).
   - On the video-enable command, run
     `pimpctl stream-start front <controller_ip> 55004`.
   - Decode `Ardrone3.Piloting.PCMD` (sticks) and feed ArduPilot as RC input
     (same UDP RC protocol dema-rc uses).
   - Parrot's open-source `libarsdk` (GroundSDK) has device-side pieces
     (`arsdk_publisher_*`, command codecs) that could cut the work.
2. **Controller side, MAVLink forwarding:** can't be done with the stock
   firmware untouched. Smallest change: via `adb`, add the two iptables NAT
   rules from dema-rc for UDP 14550 (and enable `ip_forward`). Without that,
   QGC gets no telemetry.
3. **Phone side:** USB-Ethernet chain as in section 5, request
   `http://192.168.53.1:7711/video` once (browser, or a tiny helper), then
   point FPV_VR_OS at the RTP port from the SDP.

Effort: the emulator is the bulk, roughly 1 to 3 weeks of evenings with
protocol captures, with real risk around which states `mppd` insists on.
Everything else is small.

### Option F: FreeFlight-style, phone on the controller's USB cable

The phone would need a custom Android app doing AOA + libmux + an ARSDK
client (Parrot's ARSDK3 Android code has this: `UsbAccessoryActivity`,
`ARDISCOVERY_MuxDiscovery`), exposing video and telemetry to QGC/FPV_VR on
localhost. Since the stock mux can't tunnel arbitrary UDP, MAVLink would
have to be translated into ARSDK messages on the Disco and back on the
phone. Plus the Disco emulator from option S. Months of work; not worth it.

### Verdict

dema-rc already does what option S needs, with open code, and uses the same
video path (pimpctl RTP forwarded over Ethernet). Option S keeps Parrot's
tuned Wi-Fi handling and LEDs but needs a protocol emulator and still needs
a small controller tweak for MAVLink. Recommendation: go with dema-rc. The
stock restream is still useful as proof that RTP over the Ethernet adapter
works, and for a first bench test with the stock Parrot Disco firmware
before installing ArduPilot.

### Side finding: maybe no Ethernet adapters at all

The controller's kernel has `cdc_ncm`, `cdc_ether` and `asix` built in, but
no sign of the RNDIS host driver. Android USB tethering presents the phone as
an RNDIS or NCM network device to a USB host. If the Fairphone can be put in
NCM tethering mode (Android 12+ reportedly has a hidden global setting for
the USB tethering function; to verify), the phone could plug straight into
the controller and appear as a network interface. Unknowns: whether `aoad`
grabs the phone first (it switches AOA-capable phones to accessory mode),
and the addressing is reversed (the phone runs DHCP), so dema-rc's scripts
(`eth0`, `192.168.53.1`) would need changing. Worth a bench test with
dema-rc.

## 9. Why USB isn't the bottleneck for video

- The controller is the USB **host** (its `aoad` daemon switches the phone
  into accessory mode); the phone is the device. Not the other way round.
- USB 2.0 high speed is 480 Mbit/s, tens of MB/s in practice with bulk
  transfers. The video is 480p or 720p H.264 at a few Mbit/s, a few percent
  of that.
- Nothing is transcoded: the Disco's encoder compresses, the controller just
  copies RTP packets (mux channel "RTP"), and the phone's hardware decoder
  decompresses.
- USB adds well under a millisecond. Latency comes from camera and encoder,
  Wi-Fi (retries, interference), the phone's jitter buffer, decoder and
  display. Quality is capped by the Disco's encoder settings and the Wi-Fi
  throughput.
- The same goes for a 100 Mbit/s Ethernet adapter.

## 10. Change of approach: nothing installed on the Disco or controller (2026-11-18)

New constraint: no modification of the Disco or the SkyController 2. That
means stock Parrot firmware on the Disco, so **no ArduPilot and no QGC**: the
phone app must speak Parrot's protocol (ARSDK) through the controller's USB
link (sections 3 and 8). The goal becomes running FreeFlight Pro, or an
equivalent, on the Fairphone 5 with its FPV headset view.

### FreeFlight Pro status

- Last version 5.2.7 (`com.parrot.freeflight3`), October 2019. FreeFlight 6
  (and its SDK, GroundSDK) does not support Bebop 2 or Disco, per Parrot.
- The app needs a ~158 MB Google Play expansion file
  (`main.50206202.com.parrot.freeflight3.obb`). Installed outside the Play
  Store, it can't download it and shows "Synchronization failed. Check your
  internet connection." Workaround: copy the `com.parrot.freeflight3` folder
  into internal storage `Android/obb/` (not the SD card). On Android 13+ a
  file manager can't always reach `Android/obb`; `adb push` can.
- Parrot servers are gone: flight log upload, Follow Me and Flight Plan
  purchase no longer work through Parrot. Piloting and live video don't
  depend on servers.
- Community reports (parrotpilots.com, 2024 to 2026, anecdotal):
  - Works: Android 11, 12, 13 (including a Disco on Android 13), several
    Android 14 and 15 phones, one Android 16 report (Pixel 6, Aug 2025).
  - Problems: black live view on an Android 14 tablet (Bebop 1, Dec 2024 to
    Jun 2025, unresolved); SkyController 2 not detected over USB on some
    Android 15 phones (Feb 2026). Tips: use a data USB cable, set USB mode to
    file transfer.
  - Nobody reported on the FPV/Cockpitglasses view or on a Fairphone.

### Community "Legacy Edition" APK (Sep 2026)

parrotpilots.com threads 5930 and 5931, by "ReZulu" / "Appstudios", posted
Sep 2026: "FreeFlight Pro 5.2.7 HD - All-in-One". Downloaded from
`https://appstudios.it/freeflight/FreeFlight-Pro-5.2.7-HD-AllInOne.apk.7z` and
inspected statically (not installed, not run):

- Archive SHA-256 `1d1f2e4500962fb0458296320182bb2dc8a456092fbe79d54854e9b951a71434`,
  APK SHA-256 `ff9f033de342abf97b60cb3ac6e0b2aa63e7d1a11b36b6bdb7f918944322367e`.
- It is the original app, patched and re-signed, not a rebuild from source:
  `versionName=5.2.7`, `versionCode=50207201`, signed by a personal key
  (`CN=FreeFlight HD AllInOne, O=Personal Legacy Build, C=IT`, cert SHA-256
  `CC:F6:B2:C8:...:D9:A8`), not Parrot's.
- The OBB is embedded in `assets/`, with an added
  `com.parrot.freeflight.ObbInstaller` class that installs it, so no manual
  copy is needed.
- `minSdkVersion=17`, `targetSdkVersion=28`: installable on Android 14+
  (which only block targets below 23 to 24).
- Native libraries for both `armeabi-v7a` and `arm64-v8a` (48 each), so it
  doesn't depend on 32-bit support.
- USB accessory support declared (`android.hardware.usb.accessory`,
  `USB_ACCESSORY_ATTACHED`), which the SkyController 2 needs.
- **The FPV headset mode is there**: `FpvPilotingActivity`,
  `FpvGLRenderer`, `FpvEyesCalibrationActivity`, Cockpitglasses 1 and 2
  profiles.
- No source, no changelog, no published review. Only one Android 16 report,
  from the author. It asks for internet, location, accounts and phone state
  permissions like the original. Trust is the main issue.
- Legality: redistributing a modified Parrot app is at best grey; the author
  raised it himself.

### Options

1. **Stock FreeFlight Pro 5.2.7 + manual OBB** (Parrot-signed APK, e.g.
   from APKMirror, with its OBB). Least effort, trusted binary, FPV mode
   included. Install, then
   `adb push com.parrot.freeflight3 /sdcard/Android/obb/`. Risks: the black
   video and USB detection reports above.
2. **Legacy Edition APK**. Same app, easier install, but unknown changes by
   an anonymous person. Could be checked by diffing it against the
   Parrot-signed 5.2.7 (decompile both, compare code).
3. **Patch FreeFlight Pro yourself** (apktool or jadx, edit smali, re-sign
   with your own key) to fix a specific problem on the Fairphone. Fine for
   targeted fixes; class names aren't obfuscated (`com.parrot.freeflight...`),
   which helps. A full rebuild from decompiled code is not realistic (big app,
   native libraries).
4. **Write a small app on Parrot's open-source ARSDK3.** Parrot's `Samples`
   repo (last push 2018) has an Android `SDKSample` with
   `SkyController2Drone.java`, USB accessory handling and an
   `H264VideoView`. The controller's sticks fly the Disco by themselves, so
   the app only needs connect, video and a HUD; adding SBS rendering with lens
   correction is a small step. Catch: the prebuilt `com.parrot:arsdk:3.14.0`
   is gone (404 on JCenter and Maven Central), so ARSDK3 must be built from
   source (`arsdk_manifests`, `repo`, old NDK) in a container, then
   modernised for current Android (targetSdk, Gradle). Estimate: 1 to 3
   weeks of evenings, mostly fighting the 2017-era build.

Recommendation: try option 1 on the Fairphone first; check FPV mode, live
video and USB detection. Only if something breaks, choose between a
targeted patch (3) and our own app (4).

## 11. Open-source FreeFlight replacement: options (2026-11-18)

### Decompiling FreeFlight Pro: reference only

- Useful to read how Parrot did things (FPV renderer, Cockpitglasses
  profiles, settings), not as the base of an open-source project: decompiled
  Parrot code can't be republished under an open licence, the app is large
  (two dex files, ~48 native libraries per ABI, OpenCV, a vision model) and
  the result would never be a clean source tree.
- Fine for private, targeted patches (section 10, option 3).

### Auriga Bebop: not a good base

- Closed-source paid app by "Auriga software" (Android `com.swg.andrea.auriga`,
  iOS id1455241275), FPV with HUD, head tracking, Disco ILS landing aid and
  airspeed. Last Android update July 2021 per AppFollow.
- Decompiling it has the same problems as FreeFlight, with less to gain.
- (No "Aurora" app found for Parrot drones; Auriga is probably the one.)

### AR.Pro 3: closed, but maintained and has a VR mode

- Paid app by Shell M. Shrader (Shellware, `com.shellware.arpro3`). Listing
  claims Bebop 1/2, Disco, Mambo, SkyController integration, flight plans and
  a "Cardboard VR mode". Built on his Unified GroundSdk (below); June 2026
  commits in the fork mention an "ARPro3 parity audit", so it seems alive.
  Play Store availability and Android version support not checked.
- A turnkey candidate if closed source is acceptable.
- **Play Store check (2026-11-18):** still listed, €10.99, 10K+ downloads,
  version 1.5.1.4, **last updated Sep 30, 2018** (released Jan 10, 2016).
  Description: "Disco and Mambo+MiniCam are fully supported in addition to
  the Parrot Bebop V1 and V2", "Sky Controller V1 and V2 Integration",
  "Cardboard (VR) Mode", "Disco support including Flight Plan". A June 2020
  review says the developer doesn't answer emails.
- **Fairphone 5 compatibility: unlikely through the Play Store.** A 2018
  build targets an old API level, and since Aug 31, 2024 Google Play hides
  apps targeting below API 33 from new users on devices running a newer
  Android. Expect "made for an older version of Android" on the phone
  (check in the Play app). If the APK were obtained some other way, it
  would probably install (Android only blocks targets below 23/24) and the
  native libraries' ABI would decide the rest. It predates Unified GroundSdk,
  so it runs on Parrot's old ARSDK3. The June 2026 fork commits mentioning
  "ARPro3 parity" hint at a newer version, but none is published on Play.

### Unified GroundSdk: the real open-source base

https://github.com/UnifiedGroundSdk/groundsdk-manifest , by the same
Shell M. Shrader ("synman", also seen in the 2016 Parrot SkyController 2
forum thread).

- Fork of Parrot's **GroundSDK** (the FreeFlight 6 SDK, BSD-3-Clause,
  `Copyright (C) 2019 Parrot Drones SAS`), adding back Mambo, Bebop 1/2,
  **Disco**, Bluegrass, and controllers **SkyController 1, 2 and 3**.
- Changes: `arsdk-ng` provides the legacy ARStream2 RTP ports at connection;
  `arsdk-xml` re-exposes legacy commands; `groundsdk-android` holds the
  device, instrument and peripheral support for the legacy drones (e.g.
  `SkyController2Gamepad`, `BebopStreamServer`, `BebopLocalRtspServer`,
  Disco checks in `DeviceModels`).
- USB link to the controller is in the SDK
  (`sdkcore/.../backend/mux/ArsdkUsbMuxBackendController.java`).
- **Headset view is already in the SDK**: package
  `com.parrot.drone.groundsdk.hmd` (`GsdkHmdView`, `LensProjection`,
  `Compositor`, `Overlay`, lens mesh and shaders in `res/raw`, ~3,200 lines).
  It renders the live stream side by side with lens distortion, adjustable
  left/right lens offsets and vertical offset, a UI overlay layer for the HUD,
  and a see-through mode using the phone camera. Parrot built it for
  Cockpitglasses 3 (Anafi).
- The bundled demo app (`groundsdkdemo`) already has `HmdActivity`: it opens
  the drone's live stream in `GsdkHmdView` with sliders for the lens offsets.
- Activity: 2019 to 2020 initial work, then 9 commits on 2026-06-11/12
  ("Bebop family fixes + flight-data downloaders + Disco flight tuning",
  "per-device SkyController gamepad peripherals", "streaming bridge
  hardening", "10 crash/correctness hotfixes"). One-person project, no stars
  to speak of. Author called it "about 90% stable", mostly tested on Bebop 2
  and Anafi.
- Build: Parrot's `repo` + Alchemy native build, NDK r17b to r22 (pinned
  `21.4.7075529`), `minSdkVersion 24`, `targetSdkVersion 28`,
  `compileSdkVersion 28`. Linux host (Docker is fine).
  `repo init -u https://github.com/UnifiedGroundSdk/groundsdk-manifest`.

### OpenFlight

Parrot's open-source core of FreeFlight 7 (BSD-3) targets the Anafi Ai/USA
generation; not needed here since Unified GroundSdk already covers the Disco.
Repo not verified.

### Proposed plan for an open-source "FreeFlight FPV" app

1. **Build the fork and its demo app** in a Docker container (Ubuntu 20.04,
   NDK r21, JDK 11). Sideload `groundsdkdemo` on the Fairphone, connect by
   USB to the SkyController 2, open the Disco and its HMD activity. This alone
   may already give side-by-side video. Estimate: a weekend or two, mostly
   the build system.
2. **Small dedicated app** on top of the fork's `groundsdk` and
   `arsdkengine` modules: auto-connect to the controller, full-screen
   `GsdkHmdView`, HUD overlay (altitude, speed, battery, GPS, link quality,
   distance home), saved lens settings, a non-VR view for preflight. The
   controller's sticks fly the Disco, so no piloting UI is needed. Estimate:
   1 to 2 weeks of evenings.
3. **Modernise**: raise targetSdk to 34/35, newer Gradle/AGP, 16 KB page
   alignment of native libraries (needed for newer Android devices; the
   Fairphone 5 uses 4 KB pages). Publish on GitHub, possibly F-Droid.

Risks: one-maintainer fork; Disco + SkyController 2 over USB on recent
Android untested by me; lens geometry made for Cockpitglasses 3 (offsets
are adjustable, the distortion profile may need tuning for another headset).

## 12. Unified GroundSdk as the base of a FreeFlight replacement (2026-11-18)

Code check of `UnifiedGroundSdk/groundsdk-android` (master, June 2026):

- **Disco runs through `BebopFamilyDroneController`**, which registers what
  a HUD needs: altimeter, attitude indicator, compass, GPS, speedometer,
  battery info, flight meter, radio (`BebopRadio`), alarms, flying
  indicators, plus Disco-only `DiscoPitot` (airspeed) and
  `DiscoFlightTuning`. Also return home, flight plan, geofence, media store,
  camera, gimbal (Anafi classes reused for the shared ARSDK commands).
- **Video through the SkyController 2 is explicitly supported**:
  `BebopStreamServer` picks the "mux/arstream2" transport when the drone is
  reached through a SkyController 2/2+ over USB ("The SC2 family tunnels
  arstream2 over the MUX protocol on the USB link"), and "net/arstream2" for
  direct Wi-Fi (via a loopback RTSP bridge, `BebopLocalRtspServer`, so Parrot's
  pdraw player can consume it). Live only, no replay.
- `SkyControllerDroneFinder` and `RCController` know the Disco, so pairing
  through the controller is handled.
- Some 2026 code has `TODO(bench)` notes (e.g. SPS/PPS values in the RTSP
  bridge), i.e. not everything was verified on real hardware.
- Licence: BSD-3-Clause, so the app can be published under any open-source
  licence (keep Parrot's copyright notice).

### App architecture

- Modules from the fork: `sdkcore` (native ARSDK, mux, pdraw), `arsdkengine`
  (device support), `groundsdk` (public API, including `GsdkHmdView`).
- Screens:
  1. Connection: wait for the SkyController 2 on USB, show the Disco's
     state (controller battery, drone battery, GPS fix, calibration).
  2. Preflight / normal view: `GsdkStreamView` with a 2D HUD.
  3. **FPV headset view**: `GsdkHmdView` full screen, landscape, HUD drawn
     as the HMD overlay (altitude, airspeed, ground speed, battery, link
     quality, distance and bearing home, artificial horizon), lens offsets
     saved in preferences.
  4. Settings: a few Disco settings (return home altitude, geofence,
     video quality), lens calibration.
- The SkyController 2 sticks fly the Disco directly, so no virtual sticks.
  Controller buttons can be mapped (`SkyController2Gamepad`) to toggle
  views or start recording.
- Out of scope at first: flight plans, media download, firmware updates,
  maps.

### Effort (evenings, rough)

| Step | Work | Estimate |
|---|---|---|
| 1 | Docker build env (repo, Alchemy, NDK r21, JDK 11), build `groundsdkdemo` | 1 to 2 weekends |
| 2 | Bench test on Fairphone: SkyController 2 USB, Disco telemetry, live video, HMD activity | 1 to 2 evenings |
| 3 | Minimal FPV app (screens 1 to 3) | 1 to 2 weeks |
| 4 | HUD polish, settings, lens tuning for the headset used | 1 week |
| 5 | Modernise build (targetSdk 34+, AGP), publish | a few days to a week |

Step 2 is the go/no-go: if the demo app shows Disco video in its HMD view on
the Fairphone through the SkyController 2, the rest is ordinary Android work.

### Risks

- Single maintainer fork; fixes for Disco + SkyController 2 issues may be on
  us (possibly upstreamed to the fork).
- The native build is the most fragile part (old toolchain).
- Untested combination: Disco + SkyController 2 + recent Android.
- Lens distortion mesh made for Cockpitglasses 3; other headsets may need a
  different mesh.

## 13. Parrot Sphinx simulator (2026-11-18)

- Sphinx is Parrot's drone simulator, not a hardware emulator board: it runs
  the **real drone firmware** compiled for PC (`*-pc.ext2` images, managed
  by a `firmwared` daemon) inside a simulated world (Gazebo 7 in Sphinx 1.x;
  Unreal Engine in today's Sphinx for the Anafi generation). Apps connect to
  the simulated drone over the network as if it were the real one.
- Sphinx 1.x supported Bebop 2 and Disco (firmware index listed Disco
  1.3.0 to 1.7.1). A Nov 2022 forum reply: "sphinx 1.8 and Bebop2 are not
  supported anymore". The firmware host `plf.parrot.com` no longer resolves
  (checked 2026-11-18) and the Wayback Machine has no copy of those images.
  Current Sphinx only simulates Anafi-family drones.
- The fork's author used "a VM based instance of Sphinx" and has a 2020
  commit "Sphinx compatibility for legacy drones", so he still has a working
  setup; the images might be obtainable from him.
- Limit for us: a simulated drone is reached over the network, so Sphinx
  can't test the SkyController 2 USB path, which is the riskiest part. It
  would only help develop HUD/telemetry and the direct Wi-Fi video path.

## 14. Open-source way to simulate the Disco (2026-11-18)

No open-source simulator runs Parrot's firmware. But we don't need the
firmware: the app only sees the ARSDK protocol, telemetry and an RTP H.264
stream. So build a **"virtual Disco"** from open-source parts:

1. **Protocol: Parrot's `arsdk-ng`** (BSD-3, also in the Unified GroundSdk
   manifest). It contains the device side, not just the controller side:
   `arsdk_backend_net` (listens on TCP 44444 for the handshake),
   `arsdk_publisher_net` / `arsdk_publisher_avahi` (discovery),
   `arsdk_peer_accept` with the JSON reply, `arsdk_cmd_itf` to send events.
   `examples/device.c` (674 lines) is a ready-made fake drone (declared as
   `ARSDK_DEVICE_TYPE_BEBOP_2`). Changes: declare a Disco, add the ARStream2
   ports to the JSON, send the Disco's state events, answer the video-enable
   command by starting the stream.
2. **Flight data: ArduPilot SITL** (plane, flying-wing model; ArduPilot has
   Disco parameters). A small bridge turns MAVLink (attitude, altitude,
   airspeed, GPS, battery) into ARSDK events. Simpler first step: scripted or
   replayed telemetry.
3. **Video:** GStreamer `x264enc ! rtph264pay` to the client's port 55004.
   Source: a test pattern or a recorded Disco video at first, later a camera
   rendered by **Gazebo** (`ardupilot_gazebo` plugin) or **FlightGear**
   following the SITL flight.

Two ways to use it:

- **PC only:** the phone connects to the PC over Wi-Fi as if it were the
  Disco (direct Wi-Fi path). Tests HUD, telemetry, video decode and SBS, not
  the SkyController 2 USB path.
- **With the real SkyController 2:** the PC runs a Wi-Fi access point
  (`hostapd`, 2.4 GHz) with the paired Disco's SSID (`DISCO-xxxxxx`, open)
  and the address `192.168.42.1`. The controller connects to it as if it
  were the Disco, and the phone uses the controller's USB link as in flight.
  Tests the full chain. Unknowns: which state messages `mppd` waits for
  (capture a real session with `tcpdump` to copy them) and its product
  checks (it rejects unsupported models, so the fake must say Disco).

Effort (evenings): fake Disco with test video and scripted telemetry, a few
evenings; SITL bridge, about a week; Gazebo camera video, about another week.

Value: the real Disco on the bench (propeller off) already tests connection
and video. The virtual Disco adds flight-like telemetry for HUD work,
repeatable tests, link-loss scenarios, and development without the
hardware at hand.

Other open-source simulators (Gazebo, ArduPilot or PX4 SITL, FlightGear,
JSBSim) can supply physics and imagery but none speaks ARSDK, so the
`arsdk-ng` fake device is needed in any case.

## 15. Software-only test rig findings (2026-11-18)

- `sdkcore` has `ArsdkDebugMuxBackendController`: the SkyController's USB
  "mux" protocol over TCP to `arsdk_debug_usb_bridge_address`:
  `arsdk_debug_usb_bridge_port` (default 13579), enabled by
  `gsdk_usb_debug_enabled` (true in `groundsdkdemo`), or found by DNS-SD
  service `_arsdk-mux._tcp.`. An emulated app can use the same mux code path
  as the real controller.
- `arsdk-ng/examples/device.c` can serve mux over TCP (accept + `mux_new`)
  and the Wi-Fi protocol. Used as the reference for our TypeScript fake
  SkyController 2 + Disco (new software is TypeScript, not C).
- The fork builds `x86_64` native libraries (`groundsdk-product/buildcfg.py`).
- Host: `/dev/kvm` usable, Docker, 8 cores, 30 GB RAM, 116 GB free.
- Full plan: `PLAN.md`.

## 16. Parrot Ground SDK EULA (2026-11-18)

The Unified GroundSdk manifest carries Parrot's `EULA.md` ("PARROT GROUND
SDK End User License Agreement", version 1.1, June 2020). It applies to the
SDK as a whole; components such as Ground SDK Mobile, PDrAW and Olympe are
BSD-3-Clause (EULA 2.3). What matters for us (my reading, not legal advice):

- 2.1: we may use the SDK to build an "Application Product" and distribute
  it, "for use with the Parrot Drone". An app for the Disco qualifies.
- 2.2(1): don't distribute, link or integrate the SDK "such that the SDK
  becomes subject to another open source license". Our BSD-3 licence is
  fine; never pull GPL code into the app.
- 2.2(2): don't reverse engineer or decompile parts of the SDK that aren't
  provided as source. We only use source.
- 2.2(3): our source must carry "Portions copyright (c) 2019–Present PARROT
  DRONES SAS. All rights reserved." where our own copyright notice is
  (in `NOTICE` and `README.md`, kept out of `LICENSE` so GitHub detects plain BSD-3-Clause; also to put in source file headers).
- Sphinx has its own licence (testing only, no modification); not used.
- 4.3: Parrot warns that modifying the SDK may affect drone control and
  cause damage or injury; relevant once we patch the fork.

**Acceptance (2026-11-18):** Parrot's build tool (`dragon_build`) requires
accepting the EULA on first run and stores a marker
`~/.config/dragon_build/EULA-<md5 of EULA.md>`. The user accepted the EULA
explicitly in the chat (no terminal access at the time); the marker
`EULA-9f6684a0241a5a845bfc8395aaec0886` was created in the
`disco-fpv-dragon` Docker volume on their behalf. If the EULA text changes
(new md5), the build will ask again: re-confirm with the user, or they run
`make sdk-eula`.

**FreeFlight Pro conditions of use (2026-11-18):** the user accepted
Parrot's "Conditions of use of the Parrot FreeFlight Pro application" in
the chat, to use the community FreeFlight Pro 5.2.7 build in the test
emulator for validating the fakes; "Accept" was tapped on their behalf.
A fresh emulator needs it tapped again (the FreeFlight scenario assumes it
was).

## 17. Fakes available in the SDK (2026-11-18)

- **No protocol-level fake SkyController in the fork.** What exists:
- **`MockArsdkCore`** (`groundsdk-android/sdkcore/testing`, module
  `sdkcore-testing`): Java replacement of the native ARSDK core for tests.
  API: `addDevice(uid, type, name, ...)`, `deviceConnecting/Connected/
  Disconnected(handle)`, `commandReceived(handle, ArsdkCommand...)` to inject
  events, `expect(Expectation)` / `assertExpectation` / `assertNoExpectation`
  to check commands sent, `ArsdkCommandEncoder`/`Decoder` helpers. Parrot's
  full test suite wasn't published: only
  `arsdkengine/src/androidTest/.../AnafiBatteryGaugeUpdaterTests.java` uses
  it. Bypasses native code, mux and video.
- **`arsdk-ng/examples/device.c`** (674 lines): Alchemy module
  `arsdk-ng-device`; sends a Bebop 2 identity and one GPS event by default;
  supports mux over TCP and the Wi-Fi backend. `examples/controller.c`
  (2,421 lines, module `arsdk-ng-controller`) is the matching controller.
- `arsdk-ng/tests` has protocol encode/decode tests (`tst-arsdk`).
- Decision (2026-11-18): adapt `device.c` (C) for the end-to-end fake;
  `MockArsdkCore` for fast tests; new tools in TypeScript. See `PLAN.md`
  section 2a.

## 18. Findings from building the test rig and app (2026-11-18)

- **Parrot's stream receiver and periodic IDR frames.** libvideo-streaming
  (`vstrm_rtp_h264_rx_check_missing_frames`) treats the H.264 `frame_num`
  reset at an IDR as missing frames ("TODO: handle reset to 0 for IDR
  frame"), and the decoder then hides frames. A stream with periodic IDRs
  plays black or frozen at random. Parrot's drones stream with intra
  refresh; the fake camera now does too.
- **GroundSdk HMD shaders are invalid GLSL ES 3.00** (explicit uniform
  locations need 3.10): black headset view on strict GL implementations
  (emulator SwiftShader and ANGLE). Phone drivers may tolerate it. Fixed by
  our SDK patch (`patches/packages/groundsdk-android/`). Worth checking on
  the Fairphone 5 (Adreno 643) at checkpoint A whether FreeFlight-era
  Parrot code would have worked there unpatched.
- **Parrot's example device accepted one mux session at a time** and
  refused newcomers until pings timed out: fixed in fake-sc2 (a new
  connection replaces the old session).
- **Targeting Android 15 with the 2020 SDK** needed only
  `android:exported` on the SDK's `RcAccessoryBootstrapActivity`.

## 19. FreeFlight Pro against the fakes (2026-11-18)

- FreeFlight Pro 5.2.7 (ARSDK3) runs on the Android 15 x86_64 emulator via
  ARM translation. It talks to a SkyController 2 only through
  `UsbManager.openAccessory`, so only the Wi-Fi Disco path is testable.
- Discovery: NSD/mDNS `_arsdk-090e._udp`; the TXT record must be the raw
  string `{"device_id":"<serial>"}` (Android NSD: an attribute with that
  name and no value), else FreeFlight crashes in
  `UserProduct.extractUidFromTxtRecord`.
- Handshake (TCP 44444): FreeFlight sends its JSON with
  `arstream2_client_stream_port` / `arstream2_client_control_port`; the
  fake answers with `status`, `c2d_port`, `arstream_max_ack_interval -1`,
  `arstream2_server_stream_port 5004`, `arstream2_server_control_port
  5005`, and sends RTP to the client's stream port.
- The emulator's user-mode network can't deliver guest UDP to arbitrary
  host ports reliably, so the fake Disco runs inside the emulator (NDK
  build) and the host's fake camera reaches it through `adb emu redir`.
  `adb shell` with an inline background job never returns: start it from a
  script on the device.
- Needed for a clean HUD: `MagnetoCalibrationStateChanged(1,1,1,0)` and
  `MagnetoCalibrationRequiredState(0)`.
- Only one controller at a time: other GroundSdk apps on the emulator auto
  connect to the in-emulator Disco over Wi-Fi, so they are force-stopped.
- Piloting from FreeFlight over Wi-Fi: no take-off button for a Disco
  without a SkyController ("A remote controller is required to pilot the
  drone"); LANDING (with a dropdown) and return home / STOP RTH work and
  send `Ardrone3.Piloting.Landing` and `NavigateHome(1/0)`. FreeFlight also
  streams PCMD at about 25 Hz while connected. Its HUD shows altitude only
  with a GPS fix, and the distance from the phone's own location.
- GroundSdk (demo HUD, `ManualFlightPilotingItf.smartTakeOffLand`) sends
  `TakeOff` for the Disco (or `UserTakeOff(1)` when the drone reports it is
  moving, for a thrown take-off) and `Emergency` for the cut-out button; its
  return home sends `NavigateHome(1)` and needs `NavigateHomeStateChanged`
  to be available.
- FPV mode (Cockpitglasses 1/2, manual/auto) is in Interface settings; on a
  Disco it is toggled from the SkyController, so it can't be shown here.

## 20. Emulating the SkyController 2 USB link in the emulator (2026-11-19)

Goal: the app's real USB path (`UsbManager` accessory, `openAccessory`,
mux over the accessory's bulk endpoints), not the debug TCP bridge.
Findings on the Android 15 x86_64 emulator (kernel 6.6 GKI):

- The kernel loads `dummy_hcd`: a virtual USB device controller
  (`/sys/class/udc/dummy_udc.0`) wired to a virtual host controller
  (`usb1`, "Dummy host controller"). It also has USB/IP's `vhci_hcd`.
- The Android accessory gadget function is built in (kernel symbols
  `accessoryusb_func`, `acc_ctrlrequest`), although no `F_ACC` option
  shows in `/proc/config.gz`. `mkdir functions/accessory.gs2` in the
  configfs gadget `g1` creates `/dev/usb_accessory`.
- The USB Gadget HAL is the AOSP example stub and `sys.usb.configfs=0`:
  nothing configures the gadget; `UsbDeviceManager` uses its legacy
  handler (properties `sys.usb.config` / `sys.usb.state`, set by
  `init.usb.rc`; the sysfs writes fail harmlessly).
- `UsbDeviceManager` ignores accessories unless the
  `android.hardware.usb.accessory` feature is declared, and the emulator
  image doesn't declare it: added to `/system/etc/permissions` (emulator
  with `-writable-system`, `adb remount` twice with a reboot, then a
  reboot to load it).
- With the gadget bound to `dummy_udc.0`, the phone appears to itself as
  USB device 18d1:2d01 on `usb1`. A host program (root, usbfs) can then
  do the AOA handshake (51 protocol, 52 strings, 53 start): the kernel
  raises `ACCESSORY=START`, Android switches to `accessory,adb`, and after
  a re-enumeration (UDC unbind/bind, the HAL's job on a phone) it reports
  "entering USB accessory mode", broadcasts `USB_ACCESSORY_ATTACHED` and
  shows "Open Disco FPV to handle ...?". GroundSdk then opens the accessory
  and runs mux on it.
- Each USB function change restarts `adbd` (through `none`), and init
  kills everything in adbd's cgroup: a helper started from `adb shell`
  must move itself out (`/sys/fs/cgroup/cgroup.procs`). The test runner's
  adb calls retry once after `wait-for-device`.
- Result: `aoa-host/aoa_host.c` (NDK, runs in the emulator) plays the
  SkyController's USB side and relays to `fake-sc2 --mux`; `make
  check-usb` runs all app scenarios this way, green, including a real
  unplug and replug (`unplug` on the fake closes the session; aoa-host
  unbinds and rebinds the gadget and does the handshake again).

- **FreeFlight Pro over the emulated USB** (2026-11-19): with
  `make emu-usb`, our app uninstalled and FreeFlight installed, Android
  offers "Open FreeFlight Pro to handle Fake SkyController 2"; FreeFlight
  then connects to the fake SkyController 2 and the Disco behind it, shows
  the controller page (USB icon, battery, serial, versions), live video,
  telemetry, and, with a controller present, its hand-launch and
  Cockpitglasses buttons. Gaps it showed, fixed in the fake: controller
  serial (`ProductSerialChanged`) and magnetometer calibration
  (`MagnetoCalibrationState` and `V2`, else a red CALIBRATION alert).
  FreeFlight keeps a stuck frame when the stream's resolution changes
  mid-stream (test pattern 1280x720 to another size): the simulator's
  camera is 1280x720 too. FreeFlight takes off a Disco from the
  controller's own take-off button (no app command): fake-sc2's `button
  takeoff` / `button rth` on a button the app didn't grab now act on the
  drone like the real controller. Reference screenshots of FreeFlight's
  Cockpitglasses mode flying the simulated Disco:
  `docs/screenshots/freeflight-cockpitglasses-sim.png` (small central
  green text cluster, dashed horizon, centre marker, home arrows).

## 21. Simulated Disco with a camera (2026-11-19)

Chosen stack (all in Docker, `make sim`):

- **ArduPilot SITL, ArduPlane 4.5.7**, model `plane-elevon` (flying wing),
  `sim/disco.parm`: take-off to 50 m, 80 m circles, return home at 50 m,
  simulated 3S 2700 mAh battery, no failsafes. ArduPilot is GPLv3 and
  FlightGear GPLv2: separate programs in containers, not linked into the
  BSD app. ArduPilot flew on the real Disco (community port), so its
  flight behaviour is a fair stand-in.
- **FlightGear 2020.3** as a pure viewer (`--fdm=external`, native-fdm from
  SITL), invisible `ufo` aircraft, view from the nose = the Disco's camera.
  Findings:
  - Mesa's software rasterizer (llvmpipe) is unusable for FlightGear's
    scenery: about one frame per five minutes (main thread blocked on
    texture uploads), even with 3D clouds and random objects off.
  - The host has an Intel Arc iGPU (Lunar Lake): VirtualGL 3.1's EGL back
    end (`vglrun -d /dev/dri/card1`) renders on it and draws into Xvfb;
    15 fps (the throttle) at 960x540. Lunar Lake needs Mesa 24.1+, hence
    Debian trixie (Mesa 25); EGL wants the card device, not the render node.
  - trixie's `flightgear-data-base` (2020.3.19+dfsg) has no terrain at all;
    bookworm's (2020.3.17) ships Hawaii and Iceland: copied in a build
    stage. Home is Kapiolani Park, Honolulu (Waikiki towers, Diamond Head,
    coast). FlightGear starts at the home position so its tiles are loaded.
  - SITL sends a native-fdm packet every physics step; FlightGear reads one
    per frame and lags by minutes (full UDP queue). The bridge keeps only
    the latest packet (5503) and forwards it at the frame rate (5504).
  - FlightGear asserts in D-Bus without a session bus:
    `DBUS_FATAL_WARNINGS=0`.
  - GStreamer captures the Xvfb display (ximagesrc), H.264 intra-refresh,
    RTP to fake-sc2's video input: the same path as the test pattern.
- **Bridge** (`sim/bridge`, TypeScript, own minimal MAVLink codec, no npm
  runtime dependency): SITL telemetry (attitude, position, speeds, VFR_HUD,
  battery, GPS, home) to fake-sc2's control socket at 10 Hz (`sim
  external`); the app's piloting commands, queued by fake-sc2 (`events`),
  to ArduPlane: take-off = mode TAKEOFF + arm, then LOITER at 50 m
  (circling, the Disco's "hovering"); return home = RTL, stop = LOITER;
  landing = a two-item mission (home, NAV_LAND at home) in AUTO; emergency
  = forced disarm. Flying states map Disco semantics (`model.ts`,
  unit-tested). `--auto-takeoff` takes off once the EKF uses GPS.
- fake-sc2's control socket now takes several clients (tests and the
  bridge at once).
- Check: `tests/scenarios/sim-flight.yaml` (circling at about 50 m, live
  camera video, fighter HUD, headset with the same picture in both eyes),
  green over the emulated USB accessory as well.

## 22. Disco firmware and FreeFlight vs the fake (2026-11-19)

Question: which features of the real Disco the fake lacked, and adding
them.

### Sources examined (static analysis only)

- **Disco firmware 1.7.1**, `https://firmware.parrot.com/Drones/090e/1.7.1/ALL/disco_update.plf`
  (41,222,212 bytes, SHA-256 `f52f226d04c986b69e4962247d09b95ac634116a5eae7d183523ce9e7f70a056`;
  1.7.0 is at the same path). The PLF's entries are gzip streams, each
  starting with its file path: `usr/bin/dragon-prog` (the flight program),
  `etc/default-dragon.conf` (default settings), `etc/parameters.conf`
  (flight controller tuning), `usr/lib/libarsdk.so`, `libarcommands.so`.
- `dragon-prog` keeps the ARSDK3 generator and decoder symbol names:
  `ARCOMMANDS_Decoder_Set<Command>Cb` lists the **79 commands it handles**,
  `ARCOMMANDS_Generator_Generate<Event>` the **104 events it sends**. The
  flight program is shared with the Bebop line, so animations, headlights
  and Sequoia appear too; the Disco-specific ones include
  `PilotingSettings.CirclingDirection/CirclingAltitude/PitchMode`,
  `Piloting.Circle`, `Calibration.PitotCalibration`, `Mavlink.Start/Pause/Stop`,
  `PilotingState.LandingStateChanged`, the `Rc` feature (RC receiver).
  Not decoded, so never answered: `PilotingSettings.CirclingRadius`,
  `MaxTilt`, `BankedTurn`, `Antiflickering.SetMode`.
- `default-dragon.conf`: max altitude 150, min altitude 5, max distance
  2000 with no-fly-over on, circling clockwise at 50 m, pitch mode normal,
  return home delay 10 s, home type take-off, video auto-record on,
  stabilisation off, 30 fps, picture format snapshot, Wi-Fi auto 2.4 GHz
  channel 1, outdoor, country US with auto-country.
- `parameters.conf` (flight controller): take-off transition at 50 m, cruise
  `NAV_DEFAULT_AIRSPEED` 12 m/s (2 to 20 m/s in flight), default circle
  radius 30 m (home and landing too), max turn rate 35 deg/s, circular
  landing by default (radius 30 m, 10 m/s, 10 deg slope), battery
  percentage table for the 3S pack, pre-flight checks (battery 10 %).
- Setting **ranges** are computed at run time (only "cannot be set to %f.
  Max value is %f." strings): not in any file. The fake uses plausible
  ones (max altitude 25 to 150, min altitude 5 to 50, max distance 10 to
  2000, circling altitude 25 to 150, camera tilt and pan +-80): to check on
  the bench.
- **FreeFlight Pro 5.2.7** (the community re-signed APK, section 19): its
  dex has `$SwitchMap` tables only where its own code switches on an ARSDK
  enum, which shows what it really uses for a Disco: circling direction,
  pitch mode, landing state, alert state, home type chosen, picture and
  video record states and events, video settings, Wi-Fi selection,
  security and authorised channels, pitot and magnetometer calibration
  states, sensors list, Mavlink playing state, RC receiver states, motor
  errors. Its classes `FixedWingSettingsBuilder`, `CirclingDirectionState`,
  `PitchModeState`, `LoiterSwitchButtonValueChangedListener` (the loiter
  button: `Piloting.Circle`) match. Earlier fake logs showed FreeFlight
  pushing its stored settings (max altitude, circling, home type, return
  home delay 3 s, picture format) since the fake never reported any.
- GroundSdk builds for a Disco (`BebopFamilyDroneController`): geofence,
  `DiscoFlightTuning` (circling), `DiscoPitot`, return home, flight meter,
  system info, magnetometer, motors, sensors state, media store, removable
  storage, Wi-Fi access point, camera, gimbal, alarms: all fed by the
  events above.
- Decompiling FreeFlight with jadx was not done (running the downloaded
  decompiler was refused by the session's permission check); the string
  analysis was enough.

### What the fake now does (`fake-sc2/src/disco.c`, flight in `fake_sc2.c`)

- Answers AllSettings and AllStates like the Disco: identity (serial
  high/low, CPU id, motor and GPS versions, ARCommands version, run id),
  all piloting, return home, camera, Wi-Fi and flight plan settings, motor
  error states, flight meter, sensors, internal storage (32 GB, photo and
  video counters), picture and video states, alert, calibrations, home type
  availability, camera orientation and velocity range, flight plan
  availability and components, Mavlink state.
- Every setting the firmware decodes is stored (kept across connections),
  clamped to its range and echoed; `Common.Settings.Reset` restores the
  defaults.
- Media: `PictureV2` (busy, taken, ready, counters), `VideoV2` start/stop
  with events and timestamps; video auto-record from take-off to landing.
- Calibrations: magnetometer axis by axis, pitot in progress then done;
  flat trim.
- Wi-Fi: scan results, authorised channels (US), manual or automatic
  channel, WPA2 key.
- Alerts from the battery level (low 20 %, critical 10 %, almost empty 5 %:
  assumed thresholds), or forced (`alert`).
- Flight: the controller's sticks (`sticks`) or the app's PCMD fly it
  manually (bank to turn, pitch to climb, throttle for speed, inverted
  pitch mode), `Piloting.Circle` circles where it is, return home flies
  over home then circles it (asked, after `return_home_delay` without the
  controller, on a critical battery), circular landing at the firmware's
  speed and slope with `LandingStateChanged SPIRAL`, geofence, battery
  drain (45 min), flight plan playing/paused/stopped (takes off if needed,
  then circles home), `Common.Reboot` (the Disco gone for 15 s).
- With the simulator (`sim external`): `Piloting.Circle` = LOITER, the
  sticks = ArduPlane FBWB with RC overrides (checked: stick back climbs,
  centred holds altitude and heading), the circling settings become
  `TKOFF_ALT`, `ALT_HOLD_RTL` and the sign of `WP_LOITER_RAD`/`RTL_RADIUS`,
  and the link-loss and critical-battery returns are sent as `home_start`.

### Still missing

- Flight plan files: GroundSdk and FreeFlight upload the Mavlink file by
  FTP before `Mavlink.Start`; the fake has no FTP server, so a plan's
  waypoints are not flown (only its playing state).
- Media download (FTP or HTTP listing), the RC receiver feature (`Rc.*`,
  for a hobby radio on the Disco), over-heat events,
  `Common.Network.Disconnect`: not modelled.
- Real ranges and alert thresholds: assumed values, see above.

### GroundSdk crash on a malformed value

The first `make check` with the new states failed every app scenario: the
app crashed on connection (`ArrayIndexOutOfBoundsException` in GroundSdk's
`AnafiMotors`), which parses `MotorSoftwareVersionChanged` as
`major.minor.type.count` and indexes the fourth field when there are at
least three. The fake sent "1.0.0"; it now sends "1.0.0.1" (one motor),
and the settings probe checks the format. The protocol tests can't see
such crashes: values GroundSdk parses need the app scenarios too. Also seen
since the Disco reports its internal storage: GroundSdk's media store tries
an FTP session through the controller, which times out (no FTP in the
fake; a warning, not a failure).

### Test rig finding: TCP_NODELAY (see below for the USB link)

A fresh fake took 5.6 s to answer AllStates when telemetry had just been
sent: ARSDK sends acknowledged commands one at a time, each waiting for a
small ACK frame, and with Nagle's algorithm plus delayed ACKs on the mux
TCP socket each waited about 80 ms (a USB link has no such delay). The
fake's mux socket and the probe now set `TCP_NODELAY` (aoa-host already
did): 63 ms. GroundSdk's own debug bridge socket still uses Nagle on the
app's side.

### Protocol tests

`make fake-test` (part of `make check`, no emulator): `disco-probe`
(`fake-sc2/src/probe.c`), a scripted ARSDK controller connecting over mux
like the phone, runs `tests/fake/disco-settings.probe`,
`disco-states.probe` and `disco-flight.probe` against a fresh fake each
(about 90 s). `tests/sim/sim-disco.probe` checks the same piloting through
the simulator (`make fake sim`, then run it with disco-probe by hand).

## 23. FreeFlight against the fakes, controller mapper, failures, map (2026-11-19)

### FreeFlight Pro decompiled (jadx 1.5.1) and exercised

- Its device controllers (`com.parrot.controller.devicecontrollers`) map
  each user action (`userRequested*`, 98 of them) to an ARSDK command: the
  static list of everything FreeFlight can send. For the SkyController 2 it
  includes the Mapper (`MapButtonAction`, `MapAxisAction`, `SetExpo`,
  `ResetMapping`, `Grab`), `Skyctrl` access point, Wi-Fi, calibration and
  co-piloting commands; for the Disco, the settings, media, calibrations,
  Mavlink, `Rc.*` (an RC receiver) and `Piloting.Circle`.
- The SkyController 2 firmware's daemon (`mppd`, firmware 1.0.9) uses 85
  ARSDK commands: the Mapper feature, access point settings, co-piloting,
  its own GPS and battery, Wi-Fi country, drone manager; it also sends
  `Camera.Velocity` and `Orientation` to the drone itself (its sliders).
- In flight FreeFlight grabs nothing and relies on the controller's own
  mapping (Mapper lists); on its control mapping screen it grabs buttons
  0xF018 and the sticks, then all inputs while waiting for one.
- Driven over the emulated USB link (all screens: piloting, recording,
  photo mode, settings Interface, Piloting, Safety, Video, Network, the
  controller's page, buttons mapping and reset, calibration), FreeFlight
  sent 34 distinct commands; the fake handles all of them but
  `Ardrone3.Antiflickering.SetMode` and `PilotingSettings.MaxTilt`, which
  the real Disco's flight program doesn't decode either (no answer is the
  right answer). Its mapping screen shows the fake controller's mapping
  (throttle and loiter on J1, pitch and roll on J2, camera tilt on L1,
  take-off/land), and its pictures show the exposure on R1: the assumed
  default mapping matches. Its calibration page reads the fake controller
  as calibrated. FreeFlight pushes its own stored settings on each
  connection (return home delay 3 s, picture format...).
- `unhandled` on the fake's control socket lists the commands nothing
  handles, for finding gaps with any app.

### FreeFlight's acknowledgement latency (investigated 2026-11-20)

Symptom: over the emulated USB link FreeFlight logs most acknowledged
commands as cancelled after its 5 retries, and the fake logs late
acknowledgements ("ACK: Bad seq"); FreeFlight's UI can lag. Our app
(GroundSdk) on the same link: no timeout, no late acknowledgement.

Measured (aoa-host `AOA_TRACE=1` now decodes the mux stream and logs each
ARSDK frame and acknowledgement with its time):
- FreeFlight command to the fake's acknowledgement back on the USB link:
  median 9 ms, max 33 ms. The relay and the fake are not the problem;
  aoa-host's USB writes stay under 20 ms but for a handful.
- The fake's frame on the USB link to FreeFlight's acknowledgement:
  median 16 ms, but growing by about 100 ms a second after the first slow
  moment, up to 4.8 s. FreeFlight's receiver logs each frame within 1 ms
  of aoa-host writing it: the frames are received at once, the
  acknowledgements leave late.
- The cause is in FreeFlight's network library (ARSDK3 libARNetwork,
  open source, read on GitHub): an acknowledgement wakes the sender
  thread only when the acknowledgement queue was empty; queued
  acknowledgements then go out one per sender cycle (130 to 155 ms here,
  the wait being its own commands' 150 ms acknowledgement timeout); it
  acknowledges duplicates too; and acknowledgements of its own commands are
  only noticed at the next cycle, so its commands "time out" although the
  acknowledgement came back in about 10 ms, and it re-sends them.
- The fake (arsdk-ng, like the real SkyController 2: its firmware's
  libarsdk has the same table, 150 ms and 5 retries) retries after 150 ms:
  once one acknowledgement is late (FreeFlight's slow start in the
  emulator), each retry adds a duplicate acknowledgement to FreeFlight's
  queue, which never empties, and the lag only grows. FreeFlight's
  repeated requests (the Wi-Fi channel list, 21 acknowledged answers each,
  asked again every few seconds) feed it.

Whether a real phone falls into the same trap is for the bench: it should
be faster to start, but the library behaves the same. For the test rig,
`make fake FAKE_ENV="-e ARSDK_D2C_ACK_TIMEOUT_MS=500"` (arsdk-ng patch
`patches/packages/common/arsdk-ng/0001-peer-ack-timeout-override.patch`)
removes the retry storm: no timeout on the fake's side, FreeFlight's lag
stays around a second instead of several. The default stays 150 ms, as
on the real controller.

### SkyController 2 mapper in the fake (`fake-sc2/src/sc2.c`)

Mapping lists (button, axis, expo, inverted, active product) on
connection, `Map_button_action`, `Map_axis_action`, `Reset_mapping`,
`Set_expo`, `Set_inverted`, grabbed inputs (`Grab_button_event`,
`Grab_axis_event`), mapped actions (return home, take-off/land, record,
picture, exposure, emergency, centre camera, `APP_n` to the app), access
point SSID and channel, co-piloting source. Physical inputs on the control
socket: `button <name>`, `axis <name> <value>`. The Disco's default
mapping is assumed (sticks in mode 2, left slider = camera tilt, right
slider ends = exposure, front buttons = picture and record, settings, B, A
for the app): FreeFlight shows the same.

### The fisheye and the camera tilt

The Disco's 1080p camera has a fisheye lens; the stream is a crop that the
camera tilt moves (digital, no gimbal). The controller's left slider sends
`Camera.Velocity`; the fake Disco moves the tilt at up to 20 deg/s
(`max_camera_velocity_tilt`) within +-80 deg (assumed) and reports
`OrientationV2`. With the simulator, the bridge sets FlightGear's view
pitch offset (`--telnet` property server), so the picture follows. The
app shows the tilt ("CAM -20") and the fighter HUD's horizon uses the
camera's view pitch (attitude plus tilt). The test pattern (video-src)
does not move.

### Failures (`fail <name> [on|off]`)

Fake Disco: motor error, magnetometer needing calibration, pitot needing
calibration, overheat, camera KO, storage full, IMU, barometer,
ultrasound and vertical camera sensors, weak Wi-Fi signal; plus the GPS fix
lost, the video stream lost and a motor cut-out (alert, then a gliding
descent). The app shows warnings from GroundSdk's Alarms instrument
(motor cut-out, motor error, emergency, battery...), its Magnetometer,
Pitot, Radio (RSSI below -80 dBm) and GPS, with the last GPS position.
Not shown by the app yet: overheat, camera KO, storage full (no
GroundSdk component exposes them for this drone), a frozen video.

### HUD in the headset

FreeFlight's Cockpitglasses view keeps its HUD in the middle of the
picture (about 40 % of the width and 25 % of the height of each eye): the
lenses only show the middle, and the bands outside the video are not seen.
The app's headset HUD now stays in a centred area of the video (60 % by
default, a setting from 40 to 100 %), and `app-headset` checks the bands
stay black.

### Moving map

Track-up moving map (HUD mode, or over any mode with the controller's map
button, B by default, while held or until pressed again): the trail, home,
north, range ring. The last GPS position is kept (saved every 2 s, a
setting) and shown with its age when the fix or the link is lost, also in
the settings screen after a restart. Map backgrounds (XYZ Web Mercator
tiles, drawn by the app, rotated with the map, cached on disk for offline
use):
- OpenStreetMap: tiles as viewed only; its tile usage policy forbids bulk
  downloads, so no "download ahead";
- IGN aerial photos of France (Géoplateforme WMTS, Licence Ouverte, about
  20 cm): download ahead allowed;
- any XYZ URL, for example a satellite layer with your own key (MapTiler,
  Mapbox, Esri: their terms apply).
"Download the map around the last GPS position": 1.5 km, zoom 12 to 19, at
most 2000 tiles. Tests use the runner's own tile server (checkerboard
tiles) through 10.0.2.2, allowed in cleartext in debug builds only.

### Head tracking

The Disco's camera is a 1080p fisheye; the stream is a window in it,
moved digitally (`Camera.OrientationV2`, tilt and pan, GroundSdk's
Gimbal pitch and yaw). In the headset view, the phone's game rotation
vector (gyroscope and accelerometer, no compass) points it: the head's
look direction (the phone's -Z axis) relative to the pose when tracking
starts or is recentred, within the camera's range (+-80 deg assumed), at
most 10 orientations a second, on changes of 1 deg (`HeadTracker`,
`HeadMath`). The HUD's centre indicator (`CenterIndicatorView`) shows
where the centred view is: a ring at the nose's place in the picture, or
an arrow and CENTER on the edge of the HUD area when it is out of sight;
the fighter horizon follows the view (roll and pitch for the look
direction, `HeadMath.viewAttitude`). The recentre button (A by default, a
setting) makes the current head pose the centred view and centres the
camera; outside the headset it just centres the camera. The controller's
slider still moves the camera too; the head's next move takes over. In the
simulator, the pan turns FlightGear's view (heading offset).

## 24. A better simulator view than FlightGear (research, 2026-11-19)

Question: FlightGear is heavy and shows little near the ground (flat
textured terrain, coarse photos, no buildings or trees at 50 m). Web
research only (nothing installed); Intel GPU and headless feasibility
from documentation and forums, not tried.

- **Google Photorealistic 3D Tiles** (in any 3D Tiles renderer): not
  available to projects with an EEA billing address since July 2025 (HTTP
  403); terms forbid caching beyond the headers and offline use and require
  the Google attribution on screen; users report blurry, late-loading
  meshes at drone heights (10 to 500 ft). Not a fix for take-off and
  landing heights even where available.
- **French open data** is the realistic source: IGN BD ORTHO 20 cm photos
  (Etalab 2.0, WMTS), LiDAR HD terrain (50 cm, Etalab 2.0), BD TOPO
  buildings with heights (no official 3D Tiles stream: to convert).
  Grenoble-Alpes Métropole publishes a 1 m terrain model (licence not
  stated).
- **CesiumJS in Chromium** (Apache 2.0): SITL stays the only physics; a
  page sets the camera from SITL's pose (MAVLink or the native-fdm
  packets we already relay) over a WebSocket; IGN photos on LiDAR terrain,
  extruded buildings as 3D Tiles; captured from Xvfb by the existing
  GStreamer pipeline. Headless Chromium falls back to software rendering
  unless told otherwise (`--enable-gpu`, `--use-angle=vulkan`): to check on
  this Intel iGPU in Docker first. Cesium ion's free plan is
  non-commercial (15 GB a month); self-hosting the IGN data avoids it.
  Lowest effort, TypeScript, about 1 GB of memory.
- **osgEarth** (MIT): OpenSceneGraph like FlightGear, so the same
  VirtualGL EGL path should work; 3D Tiles through Cesium Native; needs a
  small C++ viewer.
- **Unreal Engine 5** with Cesium for Unreal (AirSim / Colosseum or a pose
  driven camera): the best photorealism (vegetation) but multi-GB, fragile
  headless in containers, Intel Arc crash reports on Linux, Colosseum's
  maintenance uneven; AirSim flies no planes.
- **Gazebo** with ardupilot_gazebo (LGPL): computes its own physics (the
  Disco would need modelling), no 3D Tiles, heightmaps with photos near the
  ground: not clearly better. **X-Plane 12** (commercial, Intel Arc on
  Linux unreliable, no headless), **RealFlight** (Windows) and **Webots**
  (no fixed wing with ArduPilot): rejected.
- **FlightGear** with photoscenery (orthophotos as terrain textures) and
  osm2city buildings: the cheapest step, terrain stays coarse.

Recommendation: CesiumJS in Chromium with IGN data, moved by the bridge;
fallback osgEarth; FlightGear with IGN photoscenery as an interim step;
Unreal only if photorealism with vegetation becomes a must. First step:
check WebGL on the GPU in the container (`chrome://gpu`).

Sources: https://developers.google.com/maps/communications/eea/map-tiles ,
https://developers.google.com/maps/documentation/tile/policies ,
https://community.cesium.com/t/cesium-isaac-sim-terrain-goes-black-at-drone-scale-10-500ft-google-photorealistic-3d-tiles-not-loading-close-up/45997 ,
https://www.data.gouv.fr/fr/datasets/bd-ortho-r/ ,
https://www.data.gouv.fr/datasets/mnt-lidar-hd ,
https://www.data.gouv.fr/datasets/bd-topo-r ,
https://chromium.googlesource.com/chromium/src.git/+/main/docs/gpu/using-gpu-hardware-in-headless-chrome.md ,
https://cesium.com/platform/cesium-ion/pricing/ ,
https://cesium.com/blog/2023/10/05/osgearth-adds-support-for-cesium-native/ ,
https://github.com/ArduPilot/ardupilot_gazebo ,
https://forums.unrealengine.com/t/unreal-engine-5-5-1-segmentation-fault-on-linux-ubuntu-24-10-kernel-6-11-intel-arc-a770/2227491 ,
https://ardupilot.org/dev/docs/sitl-with-realflight.html ,
https://ardupilot.org/dev/docs/sitl-with-webots.html ,
https://wiki.flightgear.org/Photoscenery , https://wiki.flightgear.org/OSM2City

## 25. FlightGear, Gazebo and CesiumJS tried (2026-11-20)

Tried on this PC (Intel Lunar Lake iGPU, `xe` driver, Mesa 25.2), all in
Docker on the GPU, the simulated Disco circling at 50 m:

| | FlightGear (`make sim`) | Gazebo Harmonic (`SIM_ENGINE=gazebo`) | CesiumJS in Chromium (`sim/cesium`) |
|---|---|---|---|
| near the ground | flat terrain with painted textures, no objects | real 3D houses, trees, lamp posts, water tower, with shadows; a made-up place | the real place: IGN 20 cm photos (cars visible), the OSM building footprints and heights as plain boxes; trees only flat in the photos, no relief (no terrain without Cesium ion) |
| flight physics | SITL's plane-elevon model | Gazebo's (ArduPilot's Zephyr, lift and drag per surface), SITL as the autopilot through its JSON interface | SITL (the view only) |
| camera rate | 15 frames/s (configured) | 25 of 30 frames/s (real-time factor 0.88) | 60 frames/s (uncapped) |
| CPU, memory | 43 %, 1.6 GB | 220 % (1 kHz physics in lock step with SITL, rendering), 1.0 GB; the view 0.8 GB more | 80 to 120 %, 0.3 to 0.6 GB |
| line-of-sight view | no | yes (`make sim-view`) | possible (a second camera) |
| state | integrated | integrated | probe only |

Screenshots: `docs/screenshots/flightgear-onboard.jpg`,
`gazebo-onboard.jpg`, `gazebo-line-of-sight.jpg`, `gazebo-chase.jpg`,
`cesium-onboard.jpg`.

Conclusion: Gazebo for now. Near the ground it is the only one with real
objects to fly around and judge height by, it gives the line-of-sight view,
and the flight is physical (a wing with lift, drag and a thrown launch)
instead of a view moved by SITL. CesiumJS is the way to fly a real place;
the two can meet: the world generator can place OSM buildings and lay the
IGN photos as the ground of a Gazebo world (T6.7).

How the Gazebo simulator works (`docker/gazebo`, `sim/gazebo`):

- Image: Ubuntu 24.04, Gazebo Harmonic (8.15) from OSRF's apt repository,
  ardupilot_gazebo built at a pinned commit (LGPL-3.0: its plugins run
  inside Gazebo, a separate program), the Fuel models downloaded at build
  time. gz-transport's CMake files want `cppzmq-dev`.
- SITL runs ArduPlane with `--model JSON:127.0.0.1` and ArduPilot's
  `gazebo-zephyr.parm` (plus `sim/disco.parm`); Gazebo's ArduPilotPlugin
  exchanges servo outputs and sensor data with it on UDP 9002/9003 in lock
  step. `docker/sitl/run-sitl.sh` takes `SITL_MODEL` and `SITL_DEFAULTS`.
- `sim/gazebo/models/disco_zephyr`: ardupilot_gazebo's Zephyr with its
  links inlined (a DetachableJoint cannot name a link inside an included
  model), a nose camera (1280x720, 80 degrees, 5 degrees down) streamed by
  ardupilot_gazebo's GstCameraPlugin (RTP H.264 to 127.0.0.1:5600) once
  enabled. `run-gazebo.sh` re-encodes it like the Disco's stream (intra
  refresh, RTCP) to fake-sc2's 55004/55005 and keeps a frame a second in
  `/tmp/camera.jpg`.
- The Zephyr's frame is x left, y back, z up, as the plugin says. Its
  worlds spawn it rolled -90 degrees, standing on its tail (pitch 90: the
  take-off check refuses with "Bad launch AUTO"). Flat on its belly it
  rests 17 degrees nose down and only skids when the motor runs, so it
  cannot take off by rolling either.
- Hand launch, like the real Disco: the world's "hand" model (fixed to the
  world, 1.5 m up) holds the Zephyr level with a DetachableJoint; after
  arming in TAKEOFF the bridge sends `throw <heading>` over UDP 5410 to
  `gz-helper.py` (Python: Gazebo's transport has Python bindings in the
  image, none for Node), which releases it and pushes it for 0.3 s with
  the ApplyLinkWrench system to 13 m/s, 12 degrees up. It climbs to 50 m
  and circles. Without Gazebo nobody listens and SITL's own model rolls.
- The world is generated (`sim/bridge/src/gazebo-world.ts`, `make
  sim-world`, fixed seed): a 4 km ground plane (low friction), a road and
  two rows of houses 250 m north, a wood south-west, a hedgerow of oaks,
  scattered trees. Fuel's Open Robotics models (CC-BY 4.0) were made for
  Gazebo Classic and colour their meshes with Ogre material scripts, which
  Harmonic ignores (black shapes): the generator writes wrappers
  (`models/fuel_*`) using their meshes with the textures as PBR albedo
  maps. The water tower's mesh names a texture file Fuel does not resolve
  (it shows untextured).
- `make sim-view`: Gazebo's GUI in a container on the host's X display
  (`DISPLAY`, `XAUTHORITY` mounted; XWayland here), a minimal GUI
  configuration with the CameraTracking plugin: its camera moves to the
  pilot's position (`PILOT=0,-15,1.7`) through `/gui/move_to/pose`, then
  `/gui/track` in TRACK mode turns it to keep the Disco in view.
  MinimalScene's `horizontal_fov` is in degrees; 40 is about the angle a
  monitor fills in the eyes, so the Disco shows at its true apparent size
  (a speck at 300 m, as in real line-of-sight flying). `VIEW=chase`:
  FOLLOW_LOOK_AT 8 m behind, 2 m up (pgains 0.5 and 1; 0.05 lags tens of
  metres behind).
- Gazebo's transport partition defaults to hostname:username: the server
  and the view (run as the host user for the X display) did not see each
  other until both set `GZ_PARTITION=disco-fpv`.
- Rendering is on the GPU: the server opens `/dev/dri/renderD128` and
  loads Mesa's gallium driver (EGL headless).

CesiumJS probe (`sim/cesium`, `docker/cesium`): Playwright's image with
Mesa's Vulkan driver added; without it Chromium falls back to SwiftShader
(3.4 frames/s). `--use-angle=vulkan` then renders on the iGPU ("ANGLE
(Intel, Vulkan 1.4.318 (Intel(R) Graphics (LNL)))"). IGN's WMTS
orthophotos as imagery, OSM buildings from Overpass extruded (height, or
levels x 3 + 2 m, else 8 m; Overpass answers 502 when busy: retried), a
camera flying an 80 m circle at 50 m. Not wired to the bridge nor
streamed.

Sources: https://github.com/ArduPilot/ardupilot_gazebo ,
https://gazebosim.org/docs/harmonic/install_ubuntu ,
https://github.com/gazebosim/gz-gui/blob/gz-gui8/src/plugins/camera_tracking/CameraTracking.cc ,
https://github.com/gazebosim/gz-msgs/blob/gz-msgs10/proto/gz/msgs/cameratrack.proto ,
https://app.gazebosim.org/OpenRobotics/fuel/models ,
https://ardupilot.org/dev/docs/sitl-with-gazebo.html ,
https://cesium.com/learn/cesiumjs/ref-doc/WebMapTileServiceImageryProvider.html ,
https://wiki.openstreetmap.org/wiki/Overpass_API

### Camera tilt and pan, textured world (2026-11-20)

Camera tilt and pan in Gazebo: the nose camera sits on a gimbal of two
revolute joints (pan about the wing's up axis, tilt carrying
`camera_link`, 5 g links, the 5 degree nose down offset kept), each driven
by a JointPositionController in velocity mode so it moves straight to its
target without springiness (Gazebo asks for an effort limit on such joints
or it ignores their range). The bridge sends `cam <tilt> <pan>` (degrees,
positive up and right) to `gz-helper.py` every 0.5 s, so a restarted
Gazebo or a lost datagram catches up; the helper publishes the joint
targets on `/disco/camera/tilt` and `/disco/camera/pan`. Checked in
flight: the controller's tilt slider (fake `axis left_slider`) took the
view straight down; pan was checked with `cam -10 70` sent to the helper
directly, since the fake's control socket cannot set it.

The ground is now textured: `gazebo-world.ts` writes `models/disco_ground`
with flat OBJ meshes whose UVs are world coordinates, so ambientCG
textures (CC0, 1K colour maps, downloaded into the image under
`/opt/disco-textures`) repeat every 4 to 10 m (a plain SDF plane stretches
one texture over all of it). There is a meadow, a patchwork of fields
turned 12 degrees (lush, dry, ploughed, stubble, mown) with grass margins
and oak hedgerows near the field, the mown take-off strip, dirt tracks, an
asphalt road, a gravel forecourt and a pond with trees, at heights of 4 to
10 cm so layers do not flicker. Cost: 232 trees instead of 155; Gazebo
231 % CPU and 1.16 GB (was 220 % and 1.0 GB), camera 23.8 fps,
real-time factor mostly 0.95 to 1.0. Seen from 50 m the textures blur to
an even colour and the stubble texture shows 10 m stripes (a large-scale
colour variation would help); the water tower's texture still fails to
load. Screenshots: `docs/screenshots/gazebo-onboard*.jpg`,
`gazebo-chase.jpg`.

Note: after a `make sim`, the fake's own flight model stays off (the
bridge drove it), so scenarios that rely on it (`app-map`: the Disco
circling home) fail until the fake is restarted (`make fake`).

## 26. Offline OpenStreetMap maps for the app (2026-11-20)

Question: the app's moving map may not prefetch OpenStreetMap's tiles,
yet OsmAnd downloads whole countries. Both are true: the restriction is on
one server, not on the data.

- **tile.openstreetmap.org** (the OSMF's servers, run on donations):
  "Offline use is not permitted", no "bulk download (scrape) tiles or
  offer prefetch features", and "Download city/country for offline use"
  is named as prohibited. Viewed tiles must be cached (HTTP headers, else
  7 days), which the app does. The policy itself points elsewhere: "If you
  require offline maps, use self-hosted tiles or a provider that
  explicitly allows offline/prefetching."
- **The data** is ODbL: anyone may copy it, convert it and ship it, with
  attribution ("© OpenStreetMap contributors"). OsmAnd does that: it
  builds its own OBF files from OSM extracts and serves them from its own
  servers (free plan limited to a few maps, an installation id sent with
  downloads); its format and servers are OsmAnd's (GPLv3 code), not meant
  for other apps.
- **Mapsforge**: prebuilt offline vector maps from OSM (ODbL) per country
  and per region at download.mapsforge.org/maps/v5 (france.map 3.3 GB, or
  per former region: rhone-alpes.map 343 MB, ile-de-france.map 201 MB,
  corse.map 26 MB; "not suitable for mass downloads", a fast mirror at
  ftp-stud.hs-esslingen.de), hillshading data at /maps/dem. The Android
  library renders them offline (XML render themes, hillshading); LGPL v3
  with §4(d) and §4(e) waived, so a BSD app can use it. Used by OruxMaps,
  Locus, c:geo.
- **Protomaps / PMTiles**: daily OSM vector basemap builds (planet about
  120 GB, ODbL produced work); `pmtiles extract --bbox=... --maxzoom=...`
  cuts a region into one file (Berlin about 84 MB). Hotlinking their builds
  is discouraged: extract and host or copy the file. MapLibre Native
  Android 11.7.0+ reads a local file (`pmtiles://file://<absolute path>`),
  but it is a full GL map view with no offline packs for PMTiles.
- **Geofabrik** .osm.pbf extracts: raw data for building any of the above.

Fit with the app: the moving map draws 256 px raster tiles into a rotating
disc on a Canvas (twice in the headset). Mapsforge renders tiles to
bitmaps from a local .map file, so it can be one more TileSource next to
OSM, IGN photos and custom URLs: the user downloads a region once (or the
app fetches it from the mirror on request, one file, not a scrape), then
the map works with no network at the flying field. MapLibre would mean a
second map view (or snapshots) for the same disc: heavier for this use.
IGN's photos (France) already allow prefetching (section 23).

Recommendation: a Mapsforge TileSource (T4.x): pick a .map file (or
download a region), render tiles on the tile threads, keep the disk cache;
attribution on the map.

### Built (T6.8)

- `MapsforgeTiles` (app): Mapsforge 0.25.0 (`mapsforge-map-android`,
  `mapsforge-themes`, plus AndroidSVG; NOTICE updated) opens the picked
  file in place through its document URI (`openFileDescriptor`, then
  `MapFile(FileInputStream, 0, null)`), so a 343 MB file is not copied.
  `DatabaseRenderer.executeJob` renders one 256 px tile with the built-in
  default theme; the bitmap is copied and the renderer's released.
- Drawn without labels and symbols, at scale 1: `AndroidGraphicFactory.
  createInstance` sets the device scale to the screen density (2.6 on the
  emulator), which drew POI icons and street names about 3 times too big
  in 256 px tiles; and on a small disc that turns with the track, text
  can't be read anyway. Roads, water, woods, fields and buildings remain.
  The default theme has no style menu to switch POIs off selectively.
- In `TileLayer`, a source with a `mapFile` renders on a single thread
  (the renderer isn't thread-safe), memory cache only (no disk cache,
  rendering is fast enough), no prefetch (nothing to download). Outside
  the file's area a tile is simply empty. Max zoom 18.
- Settings: "Map background" gains "offline map file" (after custom URL,
  so the existing scenario's cycle still holds); "Offline map file"
  opens Android's document picker (`ACTION_OPEN_DOCUMENT`, persistable
  read permission) and selects it.
- Measured on the emulator (`make map-test`, Lyon, rhone-alpes.map):
  the first tile 316 ms (theme and file index loading), then 54 ms at zoom
  15 and 26 ms at zoom 17.
- Tests: a JVM unit test for the setting; `make map-test` (an
  instrumented test: renders zooms 13, 15, 17 in Lyon from the real file,
  checks Paris is outside it, pulls the PNGs to out/map-tiles); the
  scenario `tests/scenarios/map-offline.yaml` (not in the default set, it
  needs the 343 MB file on the emulator) shows the moving map near
  Grenoble over the file. The androidTest build needed hamcrest-core
  excluded (duplicate classes with the SDK testing AAR's hamcrest 2.1).

Sources: https://operations.osmfoundation.org/policies/tiles/ ,
https://wiki.openstreetmap.org/wiki/OsmAndMapCreator ,
https://f-droid.org/en/packages/net.osmand.plus/ ,
https://download.mapsforge.org/ , https://download.mapsforge.org/maps/v5/europe/france/ ,
https://github.com/mapsforge/mapsforge ,
https://docs.protomaps.com/basemaps/downloads ,
https://maplibre.org/maplibre-native/android/examples/data/PMTiles/

## 27. Satellite view for the moving map, worldwide (2026-11-20)

Goal: satellite or aerial photos under the moving map anywhere, usable
offline (downloaded ahead), with terms that allow it in this app.

Ruled out:
- **Esri World Imagery**: offline use only through Esri content packages
  with Esri software, otherwise an ArcGIS subscription; Gaia GPS had to
  set its offline download limit to 0 tiles from 2025. Online it also
  wants an ArcGIS account. Still possible as the custom URL with your own
  key, online only.
- **Google, Bing, Mapbox, MapTiler**: keys, and offline caching needs a
  paid licence or their own SDK.
- **EOX Sentinel-2 cloudless 2017 to 2025**: CC BY-NC-SA 4.0
  (non-commercial); the 2016 layer is CC BY 4.0, which is what we use.

Chosen: open national orthophotos where they exist, the Sentinel-2
cloudless 2016 mosaic (10 m, global) elsewhere, picked per tile:

| provider | area | detail | terms | max zoom served |
|---|---|---|---|---|
| IGN Géoplateforme BD ORTHO | France | 20 cm | Licence Ouverte | 19 |
| swisstopo SWISSIMAGE (wmts.geo.admin.ch) | Switzerland | 10 cm | OGD, free use, "© swisstopo"; fair use limits (FSDI terms) | 19 |
| IGN España PNOA-MA (ign.es WMTS) | Spain, Balearics, Canaries | 25 cm | CC BY 4.0, "PNOA cedido por © Instituto Geográfico Nacional" | 19 |
| PDOK luchtfoto Actueel_orthoHR | Netherlands | 8 cm | CC BY 4.0 (Beeldmateriaal Nederland) | 19 |
| USGS The National Map, USGSImageryOnly | USA (contiguous, Hawaii) | about 1 m | public domain | 16 |
| EOX s2cloudless_3857 (2016) | world | 10 m | CC BY 4.0 | 18 served, 15 used |

How (app, `Imagery.kt`, `TileLayer`): `TileSource.SATELLITE` lists the
providers, finest first. For a tile, the providers whose box contains the
tile's centre are tried in order; one is taken when it gives a picture.
Outside its country a service answers 404 (IGN), 400 (swisstopo, USGS
404) or a blank JPEG (PDOK one colour, PNOA almost: luminance spread 0 and
9 out of 255 on 64 samples, against 60 to 150 for photos), so boxes can be
rough: a 400/404 is kept as an empty file (not asked again), a blank tile
is skipped, and the next provider is used. Beyond a provider's maximum
zoom its deepest tile is cropped and enlarged on the phone (Sentinel-2
from zoom 15, its 10 m pixels gain nothing from the server's upscaled
16 to 18; USGS from 16). Each provider has its own cache folder. The
attribution under the map is the one of the tile under the Disco. "Download
the map around the last GPS position" works for it (each tile from its
provider, up to 2000).

Probed on 2026-11-20 (zoom 15 to 19 tiles in Lyon, Madrid, Amsterdam,
Bern, Honolulu; border answers in Bern, Geneva, Barcelona, Brussels,
Antwerp). swisstopo also serves pictures outside Switzerland (Lyon), but
its box keeps it to Switzerland, after IGN. basemap.at (Austria, CC BY
4.0) timed out from here: left out for now. Other open national
orthophotos could be added the same way (Belgium, German states, Austria,
Denmark with a token, Japan GSI).

Tests: JVM unit tests (provider order at Lyon, Bern, Madrid, Amsterdam,
Honolulu, Sydney; blank detection); `tests/scenarios/map-satellite.yaml`
(real services, not in the default set) shows Grenoble, Honolulu and
Sydney. Screenshot: `docs/screenshots/app-maps-offline-satellite.jpg`
(offline file and IGN near Grenoble, USGS in Honolulu, Sentinel-2 in
Sydney).

Sources: https://www.esri.com/content/dam/arcgisonline/docs/tou_summary.pdf ,
https://blog.gaiagps.com/esriupdate ,
https://tiles.maps.eox.at/wmts/1.0.0/WMTSCapabilities.xml ,
https://cloudless.eox.at/license-non-commercial ,
https://www.swisstopo.admin.ch/en/conditions-geodata ,
https://www.geo.admin.ch/en/general-terms-of-use-fsdi ,
https://www.ign.es/resources/licencia/Condiciones_licenciaUso_IGN.pdf ,
https://www.idee.es/csw-codsi-idee/srv/api/records/spaignwmts_pnoa-ma ,
https://data.overheid.nl/en/dataset/56588-luchtfoto-beeldmateriaal-2024-7-5cm-rgb-open-data ,
https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer

## 28. FreeFlight vs our app: latency, CPU, memory (2026-11-20)

Setup: the emulator in USB mode (`make emu-usb`, the fake SkyController 2
through aoa-host), FreeFlight Pro 5.2.7 and our usb build installed side
by side, each opened in turn from Android's chooser on the same fake
Disco, hovering at 50 m. Note: FreeFlight runs through ARM translation on
the x86_64 emulator (its native code is ARM only), our app natively: that
inflates FreeFlight's CPU and code memory here, not on a real phone.

How (`tests/e2e/src/latency.ts`, `make latency`):
- the fake camera can draw the wall clock in each frame
  (`video-clock.py`, `CLOCK_CODE=1`): 26 blocks, white and black
  references then the milliseconds modulo 2^24 in 24 bits, stamped when
  the frame leaves the test source; `clockcode.ts` finds the row by its
  shape at any scale, orientation or crop and reads it back;
- screenshots by the emulator's console (`adb emu screenrecord
  screenshot`), timed in its container on the host clock like the
  camera: 120 to 170 ms each, the grab first then PNG encoding, so the
  latency is close to the lower bound ("capture started N ms after the
  frame left the camera"). Android's own `screencap` takes 260 to 330 ms
  and runs on the device clock, which was 960 ms behind the host here
  (measured by pings over a persistent adb shell, within 8 ms);
- CPU from `top` over 10 s, memory from `dumpsys meminfo`, while
  streaming.

Video, glass to glass (camera source to screen, lower bound, median):

| view | our app | FreeFlight |
|---|---|---|
| flight view / piloting view | 125 to 126 ms (38 frames, 81 to 294) | 127 to 130 ms (60 frames, 73 to 239) |
| headset view / Cockpitglasses | about 105 ms (24 frames, small centred code: `CLOCK_BLOCK=12 CLOCK_Y=234`) | not readable: its horizon line crosses the code |

The same within noise: the video path (x264 ultrafast, RTP, fake relay,
emulator network, emulated USB, MediaCodec on the emulator's decoder,
composition) sets it, and both apps use Parrot's pdraw with MediaCodec.

Telemetry (`tests/e2e/src/telemetry-latency.ts`: the fake's altitude
changed at a known host time, screenshots back to back until the
displayed value changes; static test pattern, `SPEED=0`): our app 208 to
770 ms (median between 381 and 589), FreeFlight 209 to 834 ms (median
between 615 and 788), 10 changes each. The fake puts the frame on the TCP
link within 3 to 8 ms (tcpdump in a throwaway container) and aoa-host
relays without buffering, so most of the half second is on the phone
side for both (the video crosses the same link in about 125 ms); not
split further (it would need timestamps inside GroundSdk). Our app is
about 0.2 s faster.

Inputs: neither app relays the sticks: the SkyController 2 sends piloting
to the Disco itself, so stick to aircraft latency is the same with any
app or none; what the pilot sees of it is the video latency above.
FreeFlight's own commands (return home, settings) suffer its
acknowledgement lag (section 23); our app sends only the camera
orientation (head tracking) and button grabs.

CPU and memory while streaming:

| | our app | FreeFlight |
|---|---|---|
| CPU, flight / piloting view | 53 % | 71 to 74 % |
| CPU, headset / Cockpitglasses | 75 % | 128 % |
| PSS | 71 to 82 MB | 168 to 180 MB (237 MB after a longer session) |
| RSS | 180 to 191 MB | 320 to 416 MB |
| native heap | 21 to 25 MB | 54 to 79 MB |
| Java heap | 12 to 14 MB | 14 to 23 MB |
| code | 7.5 MB | 36 to 50 MB |

Found on the way: **a camera stream restart freezes the video for good,
in both apps.** When the stream's timestamps go back (the camera
pipeline restarted), pdraw logs "timestamp rollback from X to Y,
incrementing" for every frame and nothing new is shown (still frozen 30 s
later in both); a new session (reconnect) recovers. If the real Disco's
encoder restarts in flight (resolution or mode change, a video pipeline
restart), the picture would freeze: a frozen-video watchdog that restarts
the stream is needed (T8.2).

Also: after `make sim`, `make sim-stop` now sends `sim internal` to the
fake so its own flight model runs again (the bridge had switched it off,
which broke `app-map`).

## 29. What could go wrong with the real Disco (risk review, 2026-11-20)

Facts first. The app sends no piloting, take-off, landing or return home
command; its only commands are the camera orientation (head tracking) and
button grabs on the controller. The SkyController 2 flies the Disco
without a phone, and the Disco's link loss return home depends on the
controller link, not the phone (Parrot's design, not yet checked by us).
So a dead app or phone should leave a blind pilot, not an uncontrolled
aircraft. Everything protocol related was verified only against our
fakes, built from firmware 1.7.1 (Disco), 1.0.9 (controller) and
FreeFlight.

Risks, most serious first (likelihood times impact):

1. **Video lost in the headset** (crash, freeze, decoder stall, cable,
   heat, a system dialog): a blind pilot. Evidence: the `app-unplug`
   flake (video not back after a replug, 2 in about 25 runs); a camera
   stream restart freezes the video for good (section 28); a frozen frame
   is not detected. Mitigation: the frozen video watchdog (T8.2); a
   procedure (loiter on the controller, headset off, line of sight); a
   spotter; Do Not Disturb or airplane mode (offline map); our app as the
   controller's default app.
2. **Crash on connection on Android 14 and later** (verified in the
   code): `ArsdkUsbMuxBackendController.requestRcPermission`
   (sdk/packages/groundsdk-android/sdkcore, around line 217) registers a
   receiver without an exported flag and makes a PendingIntent without a
   mutability flag. Our earlier SecurityException in USB mode was this.
   `check-usb` passed only because Android's accessory launch grants the
   permission first. Mitigation: T8.1.
3. **Real hardware differs from the fakes**: the stream through the
   controller, the AOA handshake with a real controller, unexpected values
   in the SDK's parsers (precedent: the AnafiMotors index crash, section
   22), GPU differences for the headset shader. Mitigation: the bench
   with logcat recorded; harden the motor version parsing; a crash
   handler that restarts the flight view.
4. **A button grab swallowing a flight action**: B, A and settings are
   grabbed assuming the default mapping; a pilot's remapping would be
   taken silently, and the release of grabs when the app dies is unknown.
   Mitigation: T8.3, and the bench check.
5. **A misleading HUD**: tilt range and sign, alarm thresholds and
   settings ranges are assumed; the fighter horizon adds the tilt; head
   tracking drifts (no compass in the game rotation vector) and the camera
   stays off centre when the headset view pauses. Mitigation: compare
   with the picture and with FreeFlight on the bench; recentre on pause;
   head tracking last.
6. **Phone heat and battery in a headset**: decoding, two eyes, sensors,
   map rendering; tiles on mobile data. Mitigation: a 30 minute soak; maps
   downloaded ahead or offline, airplane mode.
7. **Acknowledgement lag like FreeFlight's**: not seen with our app on the
   emulated link, and we send few acknowledged commands. Bench: watch for
   retries in logcat.
8. **Wrong build or Disco settings**: the debug build has the TCP bridge
   (install the usb build); the app sets none of the Disco's settings
   (geofence, return home altitude and delay): check them in FreeFlight
   once.

First real flight checklist:
1. T8.1 and T8.2 done; usb build installed; Disco settings checked in
   FreeFlight.
2. Bench outdoors with a GPS fix, propellers off, logcat recorded:
   connection, telemetry, video, firmware versions.
3. Unplug the phone and force stop the app: the sticks still move the
   surfaces.
4. With the app in front: take-off/land, return home, loiter and record
   buttons act on the Disco; map, HUD and recentre buttons work; kill the
   app: those buttons are back to normal.
5. Pull and replug the cable 5 times: the video comes back, the HUD
   flags the outage.
6. Tilt slider through its range: the horizon and CAM value match the
   picture; battery, GPS, alarms match FreeFlight.
7. 30 minutes in the headset view: temperature and battery logged.
8. Video latency on the real link (film the screen and the Disco, or the
   clock method with a screen showing a clock in front of the camera).
9. First flight in the flight view, line of sight, head tracking off,
   with a spotter; loiter and return home from the controller.
10. Then the headset, put on at altitude in loiter; rehearse a video loss;
    head tracking last.

## 30. Before the first flight: T8 work, and crashes in the simulator (2026-11-20)

### A file cleaner damaged the tree (2026-11-20, about 12:40)

Something outside this project's tools deleted, at 12:39 to 12:40, files
that had an identical copy elsewhere under the project folder, the
pattern of a duplicate file cleaner: our app's layout XML files (copies in
app/build), most PNG screenshots (copies in out/ run folders),
sim/bridge/tsconfig.json, the `HEAD` file of all 37 SDK git projects and
of the repo manifest, the SDK's `.MODULE_NAME*`/licence marker files,
COPYING, `.clang-format`, Python `__init__.py`, three git blobs shared
between SDK projects (the empty blob among them), the patched HMD shaders
(restored at first to their unpatched versions, which made the headset
view black again: GL error 0x502 in glGetUniformLocation), out/sdk-aar,
object files under sdk/out/olympe-linux (the fake no longer linked) and
files in node_modules. Repaired: tracked files checked out again (main
repository and every SDK project), each SDK `HEAD` rewritten from its
reflog, the three blobs written back from copies in other projects
(`git fsck` clean everywhere), the patch 0001 shaders re-applied (every
patch checked with `git apply --check -R`), olympe-linux clobbered and
rebuilt, SDK native libraries and AARs rebuilt, node_modules reinstalled.
Whatever ran should be told to leave this folder alone: duplicates are
normal in source trees and build outputs.

### T8.1 USB permission crash (done)

Patch `0003-sdkcore-usb-permission-android-14`: the receiver registered
with RECEIVER_NOT_EXPORTED on API 33+, the PendingIntent explicit (our
package) and FLAG_MUTABLE on API 31+ (UsbManager adds the result to it).
Reproduced and fixed on the emulator: opened from the launcher with the
controller plugged in and no permission, the app crashed
(SecurityException); now Android asks "Allow Disco FPV to access ...?"
and the app connects. Scenario `usb-permission.yaml` (USB mode, run by
`make check-usb`): reinstalls the app (a USB permission lasts until the
app is removed; `make app-usb-install` leaves a copy on the device),
replugs the controller, dismisses the "Open with" prompt, opens the app.

### T8.2 Frozen video watchdog (done; one case not recovered)

- GroundSdk's views keep their last frame when frames stop, and pdraw's
  renderer signals "render ready" on its own timer (GLES2 renderer,
  GLES2_RENDERER_DEFAULT_DELAY_MS) even without a new frame: about 28 a
  second through a freeze, so frame-ready callbacks can't tell. Patch
  `0004-stream-frame-count`: the native renderer counts distinct frames
  rendered (the play timestamp changes, seen in the overlay callback,
  which runs on every render), returned with the content zone (a fifth
  element, no new JNI call) and exposed as `getFrameCount()` on
  GsdkStreamView and GsdkHmdView.
- `VideoWatchdog` (app, unit-tested), polled every 250 ms: fewer than 3
  frames in 1 s is a frozen or slow picture (warnings VIDEO FROZEN, VIDEO
  SLOW with some frames, NO VIDEO before the first); no new frame at all
  for 3 s restarts the stream, at most every 4 s (VIDEO LOST, RESTARTING
  until frames flow). A new stream gets 4 s for its first frames before
  NO VIDEO, and 10 s before a restart. Only total silence restarts: a
  restart blanks the picture, worse than slow, and the emulator (a busy
  phone too) renders slowly at times; the first versions restarted on a
  low frame rate and blanked the headset view during the lens tests.
- Restart = release the stream and the view's sink, open it again (as a
  new screen does); a stop and play of the same stream was not enough.
- Scenario `app-video-frozen.yaml`: the fake stops forwarding video (`fail
  video`): the warning shows; video back: it goes, the picture moves.
- Not recovered: a camera stream restart (the timestamps go back, section
  28). Each new pdraw session gets frames stamped 0 ("duplicate timestamp
  (0)") and stalls again within half a second; only a new connection (app
  restarted) recovers, in our app and in FreeFlight. The warning shows;
  the pilot knows the video is gone. To look at if a real Disco ever does
  it (an encoder restart): the state kept per connection below pdraw.

Found by the watchdog: in the headset view, after about 4 minutes of the
lens scenario (many UI dumps), the decoder stalls ("no buffer available";
the goldfish codec's 4 output buffers all held) and new sessions don't
recover while the headset view is up; `app-lens.yaml` fails at its stereo
check (it used to pass on the frozen frame). Not reproduced with a short
lens session nor in the flight view. To investigate (T8.9): frames not
released by the HMD rendering path, on a real phone too.

### T8.3 Safe button grabs (done)

`ButtonSafety` (unit-tested): a button the controller's mapping uses for
a Disco action (any action but APP_ACTION_*, alone or in a combination)
is never grabbed; the HUD says so ("B: RETURN HOME, NOT TAKEN"). The
mapping is read from SkyController2Gamepad.getMapping(DISCO); nothing is
grabbed until it is known; checked again on every gamepad update. With
the default mapping the app takes SETTINGS, B (map) and A (recentre):
`app-hud-modes.yaml` now clears the settings first and expects 0x19 (it
passed before only because an earlier scenario had left the recentre
button set to none).

### T8.4 Telemetry delay (improved)

- On the device clock (an altitude change sent from inside the emulator
  to the fake, `nc 10.0.2.2 4322`), GroundSdk's callback comes 11 to 94
  ms after the send. The device clock is not usable against the host's
  over minutes: its offset moved from -960 to -553 ms and jumps by about
  half a second between samples.
- The rest was drawing: every telemetry message re-rendered the whole
  HUD, and the emulator's UI frames are slow (median 26 ms, 90th
  percentile 117 ms, 99th 300 ms, software GL at 2400x1080). Renders are
  now coalesced to one per display frame (`Hud.submit`, Choreographer):
  telemetry to screen went from a median between 381 and 589 ms to
  between 155 and 266 ms (`telemetry-latency.ts`, 10 changes), close to
  the video's 125 ms on the same rig.
- Found with the crash tests: the bridge sent `flying_state` every 100 ms
  and the fake resent the GPS fix with every position; both are
  acknowledged commands, one in flight at a time per buffer, so over the
  emulated USB link they queued faster than they were acknowledged and
  the app showed states seconds late (still FLYING long after landing).
  Now sent on change (and every 5 s), like the Disco; satellites,
  battery and return home too.

### T8.5 Headset (partly)

Head tracking stopping (headset view left or paused) now sends the camera
back to centre. The 30 minute soak and head tracking off for the first
flights are for the bench.

### Crashes in the simulator

The bridge watches for crashes once the plane has been above 10 m since
armed, outside a landing it commanded (`detectCrash`, unit-tested):
- an impact: near the ground (3 m), the speed falls by 6 m/s within 0.6 s
  to a near stop (nose in), or the plane was sinking at 2.5 m/s or more
  and its descent stops at once;
- slid to a stop: below 1.5 m/s for 1.5 s on the ground, still flying
  (FBWB's full down stick only sinks about 1.2 m/s: flown gently into the
  ground, it slides on).
What a Disco does, as fake commands: its motor stops (`fail cut_out on`,
the MOTOR CUT OUT alarm), SITL is disarmed (flying state LANDED); an
impact at 12 m/s or more also throws the battery out (`drone_link lost`):
LINK LOST, video cleared. Cleared when SITL is armed again after the
disarm; `make sim-stop` resets the fake. Options `--crash-drop`,
`--crash-link-lost`, `--no-crash`.

`make sim-crash` (test tool, `src/crash.ts`): on SITL's second MAVLink
port (5762), ArduPlane's MANUAL mode with full down elevator and full
throttle: a nose-in crash.

Checked in Gazebo with the app (USB mode):
- full down stick from circling (FBWB): slid to a stop at 1.8 m/s; within
  2 s the app shows LANDED and MOTOR CUT OUT;
- `make sim-crash`: impact at 14.5 m/s: LINK LOST, no stale values, the
  video cleared, and now the last GPS position with its age (the HUD only
  showed it with the link up before: hidden exactly when it matters,
  after a crash; its age now counts on with no telemetry).

## 31. A traditional RC transmitter on the Disco (research, 2026-11-20)

What the Disco supports (firmware 1.7.1, static analysis, the PLF
downloaded again: SHA-256 starting f52f226d04c986b6):
- Radio drivers `usr/lib/radio-drivers/libradio-sbus.so` (Futaba S.BUS,
  on `/dev/uart-sbus`) and `libradio-sumd.so` (Graupner HoTT SUMD, on
  `/dev/uart-sumd`, with a failsafe mode), loaded by `libradio.so`; the
  board init (`etc/boxinit.standalone.chuck.rc` and `.evinrudeboard.rc`)
  links `/dev/ttyPA2` to uart-sbus and `/dev/ttyPA3` to uart-sumd. The
  firmware's manifest lists Parrot's `libradio-sbus` and `libradio-sumd`
  projects. ArduPilot's C.H.U.C.K. page documents a 3-pin R/C input on
  the C.H.U.C.K.'s left side (S.BUS, DSM, SUMD, ST24 under ArduPilot; the
  Parrot firmware: S.BUS and SUMD).
- `dragon-prog` handles the ARSDK `Rc` feature (`rc.xml`): receiver
  state (connected, protocol, enabled), `enable_receiver` (refused unless
  landed: "Could not set RC receiver state: drone is not landed"),
  channel monitoring (`monitor_channels`, `channel_value` per channel:
  action and value), calibration (`start_calibration` neutral or min/max
  per action and channel type, `abort`, `reset`, `calibration_state` with
  required and calibrated actions), `invert_channel`, and the supported
  actions (`channel_action_item`: roll, pitch, yaw, gaz, take-off/land,
  emergency, return home, piloting mode, take control; channel types:
  signed or unsigned axis, monostable, bistable, tristate or rotary
  button).
- GroundSdk: no peripheral uses it; the generated `ArsdkFeatureRc` exists
  in sdkcore. FreeFlight 5.2.7 sends `Rc.*` (its RC receiver screens).

Practical points (to check on the bench):
- Receivers with S.BUS output exist for most systems (FrSky, Futaba,
  RadioMaster/ELRS, TBS Crossfire). A 2.4 GHz radio next to the Disco's
  2.4 GHz Wi-Fi to the SkyController can hurt both (ArduPilot's Disco page
  warns about receiver telemetry interfering with the Wi-Fi): an 868/915
  MHz link (ELRS 900, Crossfire), or the Disco's Wi-Fi on 5 GHz.
- The video and telemetry still need the SkyController 2 (long range
  Wi-Fi) or the phone's own Wi-Fi to the Disco (short range); the app
  stays the screen.
- Failsafe: the receiver should send no pulses on signal loss (not hold
  or preset values), so the Disco sees the radio lost.
- The "take control" action suggests a switch giving the RC priority over
  the SkyController; who wins with both, and what the SkyController's
  sticks do then, is for the bench.

Plan: T8.8.

### Built (2026-11-20)

- **Fake** (`fake-sc2/src/rc.c`): the Disco's receiver as the flight
  program would present it, from rc.xml and dragon-prog's strings, to
  check against a real Disco: receiver state and protocol; channel values
  while monitored; neutral calibration (1 s), then min/max per action
  (the channel moved fully: a centred axis both ways, a throttle across
  its travel, a switch by 60 %); inversion; supported actions per channel
  type; required actions roll, pitch, gaz, take control (an assumption);
  enabling refused in flight or before calibration. Control socket: `rc
  connect sbus|sumd`, `rc disconnect`, `rc reset`, `rc ch <n> <value>`,
  `rc sticks ...`; `disco` reports `rc=...,piloting=0|1`. When enabled
  with its take-control switch on, the radio's sticks fly the fake's own
  flight model instead of the controller's. Protocol test
  `tests/fake/disco-rc.probe`.
- Channel values are acknowledged events (rc.xml sets no buffer): sending
  all 16 five times a second, the first version queued acknowledgements
  faster than the emulated USB link returned them (the app saw the radio
  still on after turning it off). Now the whole list when monitoring
  starts and every 5 s, and each channel as it moves. The real Disco's
  rate is unknown: watch for it on the bench.
- **SDK** patch `0005-rc-receiver-peripheral`: `RcReceiver` (GroundSdk
  peripheral), its core and the Disco's controller (`DiscoRcReceiver`,
  registered in BebopFamilyDroneController, published for the Disco
  only). Channels are updated in place: the same values again change
  nothing, so the screen stays idle. `scripts/sdk-sync.sh` now also
  removes the files patches create before applying them again.
- **App**: main screen > RC transmitter (`RcActivity`, logic in `RcSetup`,
  unit-tested): the receiver's state; the calibration steps in order
  (neutral with the throttle down; roll, pitch, throttle as an unsigned
  axis since a plane radio's throttle rests at one end, the take-control
  switch; then return home and take-off/land, optional); the channels as
  bars; inversion of each calibrated action; the radio on or off (on only
  once calibrated, the Disco refusing it in flight). Scenario
  `tests/scenarios/app-rc.yaml`: the whole setup, then the radio flying
  the fake only with its take-control switch on.
- HUD: RADIO ON while the radio is enabled (its take-control switch
  decides who flies: the switch's position isn't known without channel
  monitoring, which stays on the setup screen), RADIO LOST first of all
  when its receiver is gone (the Disco's own failsafe then applies).
- Still to do: the bench (wiring, failsafe, frequencies, range, which of
  the radio and the SkyController wins), and FreeFlight's RC screens
  compared.

Sources: https://ardupilot.org/plane/docs/airframe-disco.html ,
https://ardupilot.org/copter/docs/common-CHUCK-overview.html ,
https://www.modelaviation.com/comment/88127 ,
https://firmware.parrot.com/Drones/090e/1.7.1/ALL/disco_update.plf

## Sources

- Disco firmware 1.7.1: https://firmware.parrot.com/Drones/090e/1.7.1/ALL/disco_update.plf
- jadx (decompiler): https://github.com/skylot/jadx
- OpenStreetMap tile usage policy: https://operations.osmfoundation.org/policies/tiles/
- IGN Géoplateforme WMTS (orthophotos): https://geoservices.ign.fr/services-geoplateforme-diffusion
- FPV_VR_OS: https://github.com/Consti10/FPV_VR_OS
- ArduPilot Parrot Disco setup guide: https://ardupilot.org/plane/docs/airframe-disco.html
- Controlling Parrot Disco video (ArduPilot forum): https://discuss.ardupilot.org/t/controlling-parrot-disco-video/39828
- tridge's Disco gpio.sh: https://github.com/tridge/ardupilot/blob/pr-disco-video/Tools/Frame_params/Parrot_Disco/gpio.sh
- Bebop2 video stream thread: https://discuss.ardupilot.org/t/bebop2-video-stream/34920
- Controlling Parrot drones using SkyController2 (ArduPilot forum): https://discuss.ardupilot.org/t/controlling-parrot-drones-using-skycontroller2/52647
- dema-rc: https://github.com/ArduPilot/dema-rc
- dema-rc ArduPilot install doc: https://github.com/ArduPilot/dema-rc/blob/master/docs/install-ardupilot.md
- uavpal disco-docs, SkyController 2: https://github.com/uavpal/disco-docs/wiki/skycontroller-2
- uavpal disco4g: https://github.com/uavpal/disco4g
- Parrot libmux: https://github.com/Parrot-Developers/libmux
- Parrot libARDiscovery: https://github.com/Parrot-Developers/libARDiscovery
- PixelPilot (OpenIPC): https://github.com/OpenIPC/pixelpilot
- SkyController 2 firmware 1.0.9: https://firmware.parrot.com/Drones/090f/1.0.9/ALL/mpp_update.plf
- uavpal wiki, manual SkyController 2 firmware upgrade (versions, MD5): https://github.com/uavpal/disco4g/wiki/Manual-firmware-upgrade-and-downgrade-of-Parrot-Skycontroller-2
- parrotpilots: installing FreeFlight Pro 5.2.7 on Android 10 to 15: https://parrotpilots.com/threads/how-to-install-freeflight-pro-3-v5-2-7-on-newer-android-phones-or-tablets-tested-on-android-10-11-12-13-14-15.5577/
- parrotpilots: Bebop 1 + SkyController + Android 14 (black video): https://parrotpilots.com/threads/parrot-bepop-1-parrot-skycontroller-tab-7-pro-tablet-incl-android-14-parrot-freeflight-pro-how-do-i-get-this-constellation-to-run-or-fly.5720/
- parrotpilots: FreeFlight Pro Legacy Edition: https://parrotpilots.com/threads/5930/ and https://parrotpilots.com/threads/5931/
- Drone Feature, FreeFlight Pro on Android 15: https://www.dronefeature.com/parrot/install-free-flight-pro-on-android-15-apk
- APKMirror, FreeFlight Pro versions: https://www.apkmirror.com/apk/parrot-sa/freeflight-pro
- Parrot ARSDK3 samples: https://github.com/Parrot-Developers/Samples and manifests: https://github.com/Parrot-Developers/arsdk_manifests
- Unified GroundSdk manifest: https://github.com/UnifiedGroundSdk/groundsdk-manifest
- Unified GroundSdk, Android: https://github.com/UnifiedGroundSdk/groundsdk-android
- Unified GroundSdk, build product: https://github.com/UnifiedGroundSdk/groundsdk-product
- Parrot GroundSDK Android (upstream): https://github.com/Parrot-Developers/groundsdk-android
- Shellware, AR.Pro 3: https://shellware.com/page/ar-pro-3-for-bebop-drones
- Shellware, Unified GroundSdk: https://www.shellware.com/?p=9
- Auriga Bebop (iOS): https://apps.apple.com/fr/app/auriga-bebop/id1455241275?l=en
- Auriga Bebop (Android, AppFollow): https://apps.appfollow.io/android/auriga-bebop/com.swg.andrea.auriga?country=gr
- Helicomicro on Auriga Bebop: https://www.helicomicro.com/2019/07/12/auriga-bebop-pour-bebop-et-disco-de-parrot-dispo-sur-lappstore/
- Parrot open-source drone software (OpenFlight): https://www.parrot.com/en/open-source-drone-software
- Parrot forum, "Sphinx 1.8 and Parrot Bebop 2 not supported anymore?" (Nov 2022): https://forum.developer.parrot.com/t/sphinx-1-8-and-parrot-bebop-2-not-supported-anymore/17698
- Parrot forum, old Sphinx versions availability: https://forum.developer.parrot.com/t/does-parrot-intend-to-make-the-old-version-of-sphinx-available/14917
- Parrot forum, Disco in Sphinx: https://forum.developer.parrot.com/t/problem-connecting-to-disco-drone/7754
- arsdk-ng (Unified GroundSdk fork), including `examples/device.c`: https://github.com/UnifiedGroundSdk/arsdk-ng
- ArduPilot SITL: https://ardupilot.org/dev/docs/sitl-simulator-software-in-the-loop.html
- ardupilot_gazebo: https://github.com/ArduPilot/ardupilot_gazebo
- Parrot developer forum, "Skycontroller 2 - Wifi and HDMI out" (2016-2017), archived PDF: http://parrot.disco.free.fr/wp-content/uploads/2021/06/Skycontroller-2-Wifi-and-HDMI-out-ARSDK3-_-Sky-Controller-Parrot-Developers-Forum.pdf
