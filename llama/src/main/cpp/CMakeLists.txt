cmake_minimum_required(VERSION 3.22.1)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
        # x86_64 is mostly emulators (Intel/AMD dev hosts); a runtime-dispatched
        # CPU-microarch matrix (sse42 / haswell / icelake / zen4 / …) ships ~12
        # variants at ~5 MB each, which dwarfs the actual perf win — virtualization
        # overhead dominates emulator inference, and the rare real x86_64 Android
        # device (Chromebook ARC++) isn't perf-critical either. Override the
        # global GGML_CPU_ALL_VARIANTS=ON flag to a single baseline binary.
        set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

# --------------------------------------------------------------------------
# Vendored OpenCL: build the Khronos ICD-Loader stub from source so a clean
# clone builds without the developer hand-populating the NDK sysroot. The
# Headers and ICD-Loader live as git submodules under <project-root>/vendor/
# (pinned to a coordinated Khronos release tag). vendor/cmake/FindOpenCL.cmake
# wires the resulting `OpenCL` target into llama.cpp's find_package(OpenCL),
# overriding CMake's default FindOpenCL via CMAKE_MODULE_PATH.
# --------------------------------------------------------------------------
set(VENDOR_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../vendor)
set(OPENCL_ICD_LOADER_HEADERS_DIR "${VENDOR_DIR}/OpenCL-Headers" CACHE PATH "" FORCE)
add_subdirectory(${VENDOR_DIR}/OpenCL-ICD-Loader opencl-icd-loader EXCLUDE_FROM_ALL)
list(PREPEND CMAKE_MODULE_PATH "${VENDOR_DIR}/cmake")

# Vendored llama.cpp at <project-root>/third-party/llama.cpp
# Path from this CMakeLists: cpp/ -> ../../../../ (project root) -> third-party/llama.cpp
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third-party/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
