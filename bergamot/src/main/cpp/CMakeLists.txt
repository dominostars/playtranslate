cmake_minimum_required(VERSION 3.22.1)

project("bergamot_jni" VERSION 1.0.0 LANGUAGES C CXX)

# slimt is C++20.
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --------------------------------------------------------------------------
# Vendored, patched slimt fork at <module-root>/third-party/slimt
# Path from this CMakeLists: cpp/ -> ../../../ (module root) -> third-party/slimt
# Fork patches (dominostars/slimt @ playtranslate-android):
#   - 3rd-party/xsimd vendored at 11.2.0 (the 2023 gemmology needs the old API)
#   - QMM.cc prefers gemmology for int8 while ruy is kept for float sgemm
#   - -Werror -> -Wno-error
# --------------------------------------------------------------------------
set(SLIMT_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../third-party/slimt)

# Engine config: gemmology for int8 (correct on ARM; avoids the ruy #185 bug),
# ruy for float sgemm (no BLAS on Android), internal pcre2, builtin sentencepiece.
# Set as CACHE (not FORCE) so they default the slimt options before its
# add_subdirectory reads them.
set(WITH_GEMMOLOGY            ON  CACHE BOOL "")
set(WITH_RUY                  ON  CACHE BOOL "")
set(WITH_INTGEMM              OFF CACHE BOOL "")
set(WITH_BLAS                 OFF CACHE BOOL "")
set(USE_NEON                  ON  CACHE BOOL "")
set(USE_BUILTIN_SENTENCEPIECE ON  CACHE BOOL "")
set(SLIMT_USE_INTERNAL_PCRE2  ON  CACHE BOOL "")
set(BUILD_STATIC              ON  CACHE BOOL "")
set(BUILD_SHARED              OFF CACHE BOOL "")
set(BUILD_JNI                 OFF CACHE BOOL "")
set(WITH_TESTS                OFF CACHE BOOL "")

add_subdirectory(${SLIMT_SRC} build-slimt)

# --------------------------------------------------------------------------
# JNI wrapper -> libbergamot_jni.so (slimt static-linked, self-contained)
# --------------------------------------------------------------------------
add_library(bergamot_jni SHARED bergamot_jni.cpp)

target_include_directories(bergamot_jni PRIVATE ${SLIMT_SRC})

target_link_libraries(bergamot_jni
        slimt-static
        android
        log)
