cmake_minimum_required(VERSION 3.22)
project(meetily_whisper C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# whisper.cpp is fetched at configure time (pinned release tarball), so the
# repo does not vendor it. Requires network access on the build machine.
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whisper_cpp
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.7.5.tar.gz
)
FetchContent_MakeAvailable(whisper_cpp)

add_library(meetily_whisper SHARED whisper_jni.c)

# 16 KB page-size alignment for Android 15+ devices.
target_link_options(meetily_whisper PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(meetily_whisper PRIVATE whisper log)
