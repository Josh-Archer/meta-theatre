LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := theatre_hands
LOCAL_SRC_FILES := hands.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)/vendor
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
