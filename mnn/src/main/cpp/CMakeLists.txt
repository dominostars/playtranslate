cmake_minimum_required(VERSION 3.22.1)

project("mnn-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# --------------------------------------------------------------------------
# Vendored MNN at <project-root>/mnn/third-party/MNN
# Path from this CMakeLists: cpp/ -> ../../../ (mnn module root) -> third-party/MNN
# --------------------------------------------------------------------------
set(MNN_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../third-party/MNN)

# MNN's CMakeLists reads many options from cache variables. The
# :mnn/build.gradle.kts passes the user-facing ones via -D (which CMake
# treats as the initial cache value). The set(... CACHE BOOL "" ) lines
# below default the options when -D isn't passed (e.g. a fresh Android
# Studio CMake refresh outside Gradle). They're NOT set with FORCE, so
# Gradle's -D values win on the normal path.
#
# Qwen-only build: no vision/audio/diffusion (those require additional
# MNN sub-trees, and the TG-on-MNN multimodal path is blocked upstream
# by alibaba/MNN#4463 anyway).
set(MNN_BUILD_LLM               ON  CACHE BOOL "")
set(MNN_LOW_MEMORY              ON  CACHE BOOL "")
set(MNN_ARM82                   ON  CACHE BOOL "")
set(MNN_OPENCL                  ON  CACHE BOOL "")
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "")
set(MNN_CPU_WEIGHT_DEQUANT_GEMM ON  CACHE BOOL "")
set(MNN_USE_LOGCAT              ON  CACHE BOOL "")
set(MNN_BUILD_LLM_OMNI          OFF CACHE BOOL "")
set(MNN_BUILD_OPENCV            OFF CACHE BOOL "")
set(MNN_BUILD_AUDIO             OFF CACHE BOOL "")
set(MNN_IMGCODECS               OFF CACHE BOOL "")
set(MNN_BUILD_DIFFUSION         OFF CACHE BOOL "")
set(MNN_LLM_BUILD_DEMO          OFF CACHE BOOL "")
set(LLM_SUPPORT_HTTP_RESOURCE   OFF CACHE BOOL "")
# Build MNN as a set of shared libs (AGP packages each .so into the AAR).
# Matches the official MnnLlmChat layout: libMNN.so / libllm.so /
# libMNN_Express.so / libMNN_CL.so.
set(MNN_BUILD_SHARED_LIBS       ON  CACHE BOOL "")
set(MNN_SEP_BUILD               ON  CACHE BOOL "")

add_subdirectory(${MNN_SRC} build-mnn)

# --------------------------------------------------------------------------
# JNI wrapper library
# --------------------------------------------------------------------------
add_library(${CMAKE_PROJECT_NAME} SHARED
        mnn_chat.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${MNN_SRC}/include
        ${MNN_SRC}/transformers/llm/engine/include)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llm
        MNN
        MNN_Express
        android
        log)
