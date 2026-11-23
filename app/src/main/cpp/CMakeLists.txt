cmake_minimum_required(VERSION 3.22.1)
project(notli_vocal_engine LANGUAGES CXX)

# Build the optional Demucs JNI backend. The app keeps its existing Rust music core untouched.
set(SDENGINE_JNI ON CACHE BOOL "Build the Demucs JNI bridge" FORCE)
set(SDENGINE_TOOLS OFF CACHE BOOL "Do not build desktop tools in the Android app" FORCE)
set(SDENGINE_JNI_CLASS "org/oxycblt/auxio/vocal/NativeEngine" CACHE STRING "JNI bridge class" FORCE)

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/demucs-android
                 ${CMAKE_CURRENT_BINARY_DIR}/demucs-android)
