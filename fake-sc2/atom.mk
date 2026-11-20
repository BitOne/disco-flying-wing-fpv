# Fake SkyController 2 + Disco, adapted from Parrot's arsdk-ng example
# (examples/device.c, BSD-3-Clause, Copyright (c) 2019 Parrot Drones SAS).
# Built with the SDK's olympe-linux product: make fake-build.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := fake-sc2
LOCAL_DESCRIPTION := Fake SkyController 2 + Disco for end-to-end tests
LOCAL_SRC_FILES := src/fake_sc2.c src/disco.c src/sc2.c src/rc.c
LOCAL_LIBRARIES := libpomp libulog libmux libarsdk
LOCAL_LDLIBS := -lm
include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)
LOCAL_MODULE := disco-probe
LOCAL_DESCRIPTION := Scripted ARSDK controller for the fake's protocol tests
LOCAL_SRC_FILES := src/probe.c
LOCAL_LIBRARIES := libpomp libulog libmux libarsdk libarsdkctrl
include $(BUILD_EXECUTABLE)

# Alchemy doesn't scan below a directory that has an atom.mk: include the
# system library declarations explicitly.
include $(LOCAL_PATH)/host-libs/atom.mk
