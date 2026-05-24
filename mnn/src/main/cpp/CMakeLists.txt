cmake_minimum_required(VERSION 3.22.1)

project("mnn-chat" VERSION 1.0.0 LANGUAGES C CXX ASM)

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
# OpenCL backend dropped after a Thor benchmark (Adreno 740, SD 8 Gen 2,
# Qwen 2.5 1.5B Q4): with a warm kernel cache OpenCL was 2.23× slower at
# prefill (80 vs 178 tok/s) and 2.67× slower at decode (14.6 vs 39 tok/s);
# cold runs paid an extra 12 s of kernel JIT. Decode is bandwidth-bound on
# mobile (shared DRAM with the CPU), so the GPU's compute headroom doesn't
# help, and the OpenCL Q4 path is less tuned than MNN's NEON/i8mm CPU path.
# Re-enable by flipping back to ON + a clean :mnn rebuild — no other code
# changes needed.
set(MNN_OPENCL                  OFF CACHE BOOL "")
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
# KleidiAI's CMake setup resolves source paths via CMAKE_SOURCE_DIR which,
# inside our add_subdirectory'd build, points at :mnn/src/main/cpp/ rather
# than MNN's third-party root — so it tries to load
# `mnn/src/main/cpp/source/backend/cpu/kleidiai/*.cpp` and fails. We don't
# need KleidiAI (Arm's specialized matmul accelerator) for the Qwen / E2B
# CPU path; explicit OFF.
set(MNN_KLEIDIAI                OFF CACHE BOOL "")
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
