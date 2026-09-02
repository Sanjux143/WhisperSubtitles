cmake_minimum_required(VERSION 3.22.1)
project("whispersubtitles")

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
)

file(GLOB GGML_SOURCES
    ${WHISPER_DIR}/ggml/src/ggml.c
    ${WHISPER_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_DIR}/ggml/src/ggml-backend.c
    ${WHISPER_DIR}/ggml/src/ggml-quants.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-quants.c
)

add_library(whispersubtitles SHARED
    native-lib.cpp
    ${WHISPER_DIR}/src/whisper.cpp
    ${GGML_SOURCES}
)

find_library(log-lib log)

target_link_libraries(whispersubtitles
    android
    ${log-lib}
)
