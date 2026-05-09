# Vendored shim — see llama/src/main/cpp/CMakeLists.txt for the full setup.
# Briefly: vendor/OpenCL-ICD-Loader is built via add_subdirectory before this
# runs, producing the SHARED library target `OpenCL`. This shim wires the
# resulting target into upstream callers' `find_package(OpenCL REQUIRED)` so
# the build does not need the NDK sysroot to be hand-populated.
#
# CMake selects this shim because vendor/cmake is prepended to
# CMAKE_MODULE_PATH before any add_subdirectory that calls find_package(OpenCL).

if(NOT TARGET OpenCL)
    message(FATAL_ERROR
        "Vendored FindOpenCL: target 'OpenCL' not declared. "
        "Caller must add_subdirectory(vendor/OpenCL-ICD-Loader) before find_package(OpenCL).")
endif()

set(OpenCL_FOUND TRUE)
set(OpenCL_INCLUDE_DIRS "${CMAKE_CURRENT_LIST_DIR}/../OpenCL-Headers")
set(OpenCL_LIBRARIES OpenCL)
