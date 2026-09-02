cmake_minimum_required(VERSION 3.22.1)
project("whispersubtitles" C CXX)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# Vulkan aur platform definitions
add_definitions(-D_GNU_SOURCE)
add_definitions(-DWHISPER_VERSION="1.7.4")
add_definitions(-DGGML_VERSION="1.0.0")
add_definitions(-DGGML_COMMIT="master")

# Enable GGML Vulkan GPU Backend
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
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
find_library(vulkan-lib vulkan)

target_link_libraries(whispersubtitles
    whisper
    android
    ${vulkan-lib}
    ${log-lib}
)
