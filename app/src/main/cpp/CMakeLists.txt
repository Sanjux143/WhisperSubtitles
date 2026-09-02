cmake_minimum_required(VERSION 3.22.1)

project("whispersubtitles")

add_library(
        whispersubtitles
        SHARED
        native-lib.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        whispersubtitles
        ${log-lib}
)