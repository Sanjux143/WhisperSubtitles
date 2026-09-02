cmake_minimum_required(VERSION 3.22.1)
project("whispersubtitles" C CXX)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# Core Definitions
add_definitions(-D_GNU_SOURCE)
add_definitions(-DWHISPER_VERSION="1.7.4")
add_definitions(-DGGML_VERSION="1.0.0")
add_definitions(-DGGML_COMMIT="master")

# ARM64 Hardware Acceleration (NEON + FP16 SIMD)
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    add_compile_options(-O3 -flto -march=armv8-a+simd+fp16)
endif()

# Build options
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper_build)

add_library(whispersubtitles SHARED
    native-lib.cpp
)

target_include_directories(whispersubtitles PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
)

find_library(log-lib log)

target_link_libraries(whispersubtitles
    whisper
    android
    ${log-lib}
)
