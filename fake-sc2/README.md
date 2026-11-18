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
