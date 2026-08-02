LOCAL_PATH := $(call my-dir)

RHVOICE_ROOT := $(LOCAL_PATH)/rhvoice
RHVOICE_INCLUDE := $(RHVOICE_ROOT)/include
RHVOICE_CORE := $(RHVOICE_ROOT)/core
RHVOICE_HTS := $(RHVOICE_ROOT)/hts_engine
RHVOICE_UTF8 := $(RHVOICE_ROOT)/third-party/utf8
RHVOICE_RAPIDXML := $(RHVOICE_ROOT)/third-party/rapidxml

include $(CLEAR_VARS)
LOCAL_MODULE := hts_engine
LOCAL_C_INCLUDES := $(RHVOICE_INCLUDE)
LOCAL_SRC_FILES := $(patsubst $(LOCAL_PATH)/%,%,$(wildcard $(RHVOICE_HTS)/*.c))
LOCAL_EXPORT_C_INCLUDES := $(RHVOICE_HTS)
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := RHVoice_core
LOCAL_C_INCLUDES := \
    $(RHVOICE_INCLUDE) \
    $(RHVOICE_UTF8) \
    $(RHVOICE_RAPIDXML) \
    $(RHVOICE_HTS)
RHVOICE_UNUSED_LANGUAGES := \
    $(RHVOICE_CORE)/brazilian_portuguese.cpp \
    $(RHVOICE_CORE)/esperanto.cpp \
    $(RHVOICE_CORE)/georgian.cpp \
    $(RHVOICE_CORE)/kyrgyz.cpp \
    $(RHVOICE_CORE)/macedonian.cpp \
    $(RHVOICE_CORE)/russian.cpp \
    $(RHVOICE_CORE)/tatar.cpp \
    $(RHVOICE_CORE)/ukrainian.cpp \
    $(RHVOICE_CORE)/vietnamese.cpp
RHVOICE_EXCLUDED_SOURCES := \
    $(RHVOICE_CORE)/unidata.cpp \
    $(RHVOICE_CORE)/emoji_data.cpp \
    $(RHVOICE_UNUSED_LANGUAGES)
LOCAL_SRC_FILES := $(patsubst $(LOCAL_PATH)/%,%,$(filter-out $(RHVOICE_EXCLUDED_SOURCES),$(wildcard $(RHVOICE_CORE)/*.cpp)))
LOCAL_CFLAGS := \
    -DANDROID \
    -DENABLE_PKG=0 \
    -DENABLE_SONIC=0 \
    -DHTS_EMBEDDED \
    -DMAX_VOLUME=MAX_MAX_VOLUME \
    -DDEFAULT_PUNCTUATION_MODE=RHVoice_punctuation_some \
    -DMAX_RATE=3 \
    -DDATA_PATH=\"\" \
    -DCONFIG_PATH=\"\" \
    -DVERSION=\"1.18.4\" \
    -DPACKAGE=\"RHVoice\"
LOCAL_CPPFLAGS := -std=c++11 -fexceptions -frtti
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_C_INCLUDES)
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := RHVoice_jni
LOCAL_SRC_FILES := native.cpp
LOCAL_WHOLE_STATIC_LIBRARIES := RHVoice_core hts_engine
LOCAL_LDLIBS := -llog
LOCAL_CPPFLAGS := -std=c++11 -fexceptions -frtti -DENABLE_PKG=0
include $(BUILD_SHARED_LIBRARY)
