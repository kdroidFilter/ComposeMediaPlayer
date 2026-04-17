cmake_minimum_required(VERSION 3.15)
project(NativeVideoPlayer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(JNI REQUIRED)

if(DEFINED ENV{NATIVE_LIBS_OUTPUT_DIR})
    set(BASE_OUTPUT_DIR "$ENV{NATIVE_LIBS_OUTPUT_DIR}")
else()
    set(BASE_OUTPUT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../resources/composemediaplayer/native")
endif()

if(CMAKE_GENERATOR_PLATFORM STREQUAL "x64" OR CMAKE_GENERATOR_PLATFORM STREQUAL "")
    set(TARGET_ARCH "x64")
    set(OUTPUT_DIR "${BASE_OUTPUT_DIR}/win32-x86-64")
elseif(CMAKE_GENERATOR_PLATFORM STREQUAL "ARM64")
    set(TARGET_ARCH "ARM64")
    set(OUTPUT_DIR "${BASE_OUTPUT_DIR}/win32-arm64")
else()
    message(FATAL_ERROR "Unsupported architecture: ${CMAKE_GENERATOR_PLATFORM}")
endif()

file(MAKE_DIRECTORY ${OUTPUT_DIR})

add_library(NativeVideoPlayer SHARED
        NativeVideoPlayer.cpp
        NativeVideoPlayer.h
        VideoPlayerInstance.h
        ErrorCodes.h
        ComHelpers.h
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

target_include_directories(NativeVideoPlayer PRIVATE ${JNI_INCLUDE_DIRS})

target_compile_definitions(NativeVideoPlayer PRIVATE
        WIN32_LEAN_AND_MEAN
        NOMINMAX
        NATIVEVIDEOPLAYER_EXPORTS
        _CRT_SECURE_NO_WARNINGS
)

# Baseline is SSE2 (guaranteed on x64). AVX2 codepaths are runtime-detected
# via __cpuid in ForceAlphaOpaque — MSVC allows AVX2 intrinsics without
# /arch:AVX2, so we keep the binary runnable on older CPUs.
if(MSVC)
    target_compile_options(NativeVideoPlayer PRIVATE
        /W4
        /permissive-
        /Zc:__cplusplus
        /Zc:preprocessor
        /EHsc
        /MP            # parallel compilation
        /wd4245        # MF_SOURCE_READER_* macros are unsigned-cast-from-signed
        /wd4505        # unreferenced inline helpers
    )
    # Release-only: disable RTTI & enable whole-program optimization.
    target_compile_options(NativeVideoPlayer PRIVATE
        $<$<CONFIG:Release>:/GR->
        $<$<CONFIG:Release>:/GL>
        $<$<CONFIG:Release>:/Oi>
    )
    target_link_options(NativeVideoPlayer PRIVATE
        $<$<CONFIG:Release>:/LTCG>
    )
endif()

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

set_target_properties(NativeVideoPlayer PROPERTIES
        OUTPUT_NAME "NativeVideoPlayer"
        LIBRARY_OUTPUT_DIRECTORY "${OUTPUT_DIR}"
        LIBRARY_OUTPUT_DIRECTORY_DEBUG "${OUTPUT_DIR}"
        LIBRARY_OUTPUT_DIRECTORY_RELEASE "${OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY "${OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY_DEBUG "${OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY_RELEASE "${OUTPUT_DIR}"
)

message(STATUS "Target architecture: ${TARGET_ARCH}")
message(STATUS "Output directory: ${OUTPUT_DIR}")
