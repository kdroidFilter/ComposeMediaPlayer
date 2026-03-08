cmake_minimum_required(VERSION 3.15)
project(NativeVideoPlayer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)

# Find JNI
find_package(JNI REQUIRED)

# Check target architecture
if(CMAKE_GENERATOR_PLATFORM STREQUAL "x64" OR CMAKE_GENERATOR_PLATFORM STREQUAL "")
    set(TARGET_ARCH "x64")
    set(OUTPUT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../resources/win32-x86-64")
    add_compile_options("/arch:AVX2")
elseif(CMAKE_GENERATOR_PLATFORM STREQUAL "ARM64")
    set(TARGET_ARCH "ARM64")
    set(OUTPUT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../resources/win32-arm64")
    add_compile_options("/arch:arm64")
else()
    message(FATAL_ERROR "Unsupported architecture: ${CMAKE_GENERATOR_PLATFORM}")
endif()

# Ensure output directory exists
file(MAKE_DIRECTORY ${OUTPUT_DIR})

# Define the target
add_library(NativeVideoPlayer SHARED
        NativeVideoPlayer.cpp
        NativeVideoPlayer.h
        VideoPlayerInstance.h
        Utils.cpp
        Utils.h
        MediaFoundationManager.cpp
        MediaFoundationManager.h
        AudioManager.cpp
        AudioManager.h
        HLSPlayer.cpp
        HLSPlayer.h
        jni_bridge.cpp
)

# JNI include directories
target_include_directories(NativeVideoPlayer PRIVATE ${JNI_INCLUDE_DIRS})

# Compilation definitions
target_compile_definitions(NativeVideoPlayer PRIVATE
        WIN32_LEAN_AND_MEAN
        NOMINMAX
        NATIVEVIDEOPLAYER_EXPORTS
)

# Linked libraries
target_link_libraries(NativeVideoPlayer PRIVATE
        mf
        mfplat
        mfreadwrite
        mfuuid
        wmcodecdspuuid
        ole32
        oleaut32
        avrt
        mfsensorgroup
        dxva2
        d3d11
        dxgi
        evr
)

# Configure output directory
set_target_properties(NativeVideoPlayer PROPERTIES
        OUTPUT_NAME "NativeVideoPlayer"
        LIBRARY_OUTPUT_DIRECTORY "${OUTPUT_DIR}"
        LIBRARY_OUTPUT_DIRECTORY_DEBUG "${OUTPUT_DIR}"
        LIBRARY_OUTPUT_DIRECTORY_RELEASE "${OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY "${OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY_DEBUG "${OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY_RELEASE "${OUTPUT_DIR}"
)

# Display target architecture and output directory
message(STATUS "Target architecture: ${TARGET_ARCH}")
message(STATUS "Output directory: ${OUTPUT_DIR}")
