cmake_minimum_required(VERSION 3.22.1)

project("whisper-jni")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Compile definitions for Android CPU-only build
# ---------------------------------------------------------------------------
add_compile_definitions(
    GGML_CPU_GENERIC=1
    # Registers the CPU backend device with ggml's runtime registry. Without
    # this, ggml_backend_dev_by_type(CPU) returns null and whisper_init aborts.
    GGML_USE_CPU=1
    GGML_USE_NEON=0
    GGML_OPENMP=0
    GGML_PTHREAD=1
    WHISPER_API=
    WHISPER_VERSION="whisper.cpp 0.0.0"
    _USE_MATH_DEFINES
)

# ---------------------------------------------------------------------------
# Include directories
# ---------------------------------------------------------------------------
include_directories(${CMAKE_CURRENT_SOURCE_DIR})
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/ggml/include)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/ggml/src)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/ggml/src/ggml-cpu)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/ggml/src/ggml-cpu/amx)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/ggml/src/ggml-cpu/arch)

# ---------------------------------------------------------------------------
# Source files - whisper.cpp core
# ---------------------------------------------------------------------------
set(WHISPER_SRC
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/android_compat.c
)

# ---------------------------------------------------------------------------
# Source files - ggml core + CPU backend (excluding arch-specific and vendor dirs)
# ---------------------------------------------------------------------------
file(GLOB GGML_SRC_CORE
    "${CMAKE_CURRENT_SOURCE_DIR}/ggml/src/*.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/ggml/src/*.cpp"
)
file(GLOB GGML_SRC_CPU
    "${CMAKE_CURRENT_SOURCE_DIR}/ggml/src/ggml-cpu/*.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/ggml/src/ggml-cpu/*.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/ggml/src/ggml-cpu/tiled/*.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/ggml/src/ggml-cpu/tiled/*.cpp"
)

set(GGML_SRC_ALL ${GGML_SRC_CORE} ${GGML_SRC_CPU})
set(ALL_SRC ${WHISPER_SRC} ${GGML_SRC_ALL})

add_library(whisper-jni SHARED ${ALL_SRC})

target_link_libraries(whisper-jni
    android
    log
    dl
)

# Optimize for the target device. The CPU tuning flag is per-ABI: passing an
# ARM march value to an x86_64 compiler is a hard error, which would break
# emulator (x86_64) builds.
target_compile_options(whisper-jni PRIVATE -O3 -ffast-math -fno-finite-math-only)
target_compile_options(whisper-jni PUBLIC -pthread)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(whisper-jni PRIVATE -march=armv8-a)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(whisper-jni PRIVATE -march=armv7-a)
endif()
