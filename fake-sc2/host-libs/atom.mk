# System libraries of the build container, declared as Alchemy prebuilt
# modules for the olympe-linux (native) product. The SDK's Linux product
# doesn't declare them, and some SDK modules depend on them by name.
# Scanned through ALCHEMY_TARGET_SCAN_ADD_DIRS (see Makefile, fake-build).

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := zlib
LOCAL_EXPORT_LDLIBS := -lz
include $(BUILD_PREBUILT)

# arsdk-xml's 'arsdkparser' declares a dependency on a target 'python' module
# (normally from python-native, pruned here). The fake doesn't run Python on
# the target, so an empty declaration satisfies the dependency check.
include $(CLEAR_VARS)
LOCAL_MODULE := python
include $(BUILD_PREBUILT)
