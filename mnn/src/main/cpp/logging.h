#pragma once
#include <android/log.h>

// Adapted from :llama/llama/src/main/cpp/logging.h with MNN-specific log tag
// and the ggml integration stripped — MNN's own MNN_PRINT macros (with
// MNN_USE_LOGCAT=ON, see mnn/build.gradle.kts) route directly to logcat
// under their own tag, so we don't need a custom log callback here.

#ifndef LOG_TAG
#define LOG_TAG "mnn-chat"
#endif

#ifndef LOG_MIN_LEVEL
#if defined(NDEBUG)
#define LOG_MIN_LEVEL ANDROID_LOG_INFO
#else
#define LOG_MIN_LEVEL ANDROID_LOG_VERBOSE
#endif
#endif

static inline int mnn_chat_should_log(int prio) {
    // __android_log_is_loggable is API 30+; unavailable at our minSdk (29).
    // Its per-tag system-property override isn't needed for this helper, so
    // gate purely on the compile-time threshold (severity >= LOG_MIN_LEVEL).
    return prio >= LOG_MIN_LEVEL;
}

#if LOG_MIN_LEVEL <= ANDROID_LOG_VERBOSE
#define LOGv(...) do { if (mnn_chat_should_log(ANDROID_LOG_VERBOSE)) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__); } while (0)
#else
#define LOGv(...) ((void)0)
#endif

#if LOG_MIN_LEVEL <= ANDROID_LOG_DEBUG
#define LOGd(...) do { if (mnn_chat_should_log(ANDROID_LOG_DEBUG)) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while (0)
#else
#define LOGd(...) ((void)0)
#endif

#define LOGi(...)   do { if (mnn_chat_should_log(ANDROID_LOG_INFO )) __android_log_print(ANDROID_LOG_INFO , LOG_TAG, __VA_ARGS__); } while (0)
#define LOGw(...)   do { if (mnn_chat_should_log(ANDROID_LOG_WARN )) __android_log_print(ANDROID_LOG_WARN , LOG_TAG, __VA_ARGS__); } while (0)
#define LOGe(...)   do { if (mnn_chat_should_log(ANDROID_LOG_ERROR)) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); } while (0)
