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

### Test rig finding: TCP_NODELAY

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

## Sources

- Disco firmware 1.7.1: https://firmware.parrot.com/Drones/090e/1.7.1/ALL/disco_update.plf
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
