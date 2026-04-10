cmake_minimum_required(VERSION 3.15)
project(NativeVideoPlayer C)

set(CMAKE_C_STANDARD 11)

# Find GStreamer via pkg-config
find_package(PkgConfig REQUIRED)
pkg_check_modules(GST REQUIRED
    gstreamer-1.0
    gstreamer-app-1.0
    gstreamer-video-1.0
)

# Find JNI
find_package(JNI REQUIRED)

# Source files
set(SOURCES
    NativeVideoPlayer.c
    jni_bridge.c
)

add_library(NativeVideoPlayer SHARED ${SOURCES})

target_include_directories(NativeVideoPlayer PRIVATE
    ${GST_INCLUDE_DIRS}
    ${JNI_INCLUDE_DIRS}
)

target_link_libraries(NativeVideoPlayer PRIVATE
    ${GST_LIBRARIES}
    m
    pthread
)

target_compile_options(NativeVideoPlayer PRIVATE
    ${GST_CFLAGS_OTHER}
    -Wall -Wextra -Wno-unused-parameter
    -O2
    -fPIC
)

# Determine output directory based on architecture
execute_process(COMMAND uname -m OUTPUT_VARIABLE ARCH OUTPUT_STRIP_TRAILING_WHITESPACE)
if(ARCH STREQUAL "x86_64" OR ARCH STREQUAL "amd64")
    set(RESOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../resources/linux-x86-64")
elseif(ARCH STREQUAL "aarch64" OR ARCH STREQUAL "arm64")
    set(RESOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../resources/linux-aarch64")
else()
    set(RESOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../resources/linux-${ARCH}")
endif()

# Copy library to resources after build
add_custom_command(TARGET NativeVideoPlayer POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${RESOURCE_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:NativeVideoPlayer>" "${RESOURCE_DIR}/"
    COMMENT "Copying libNativeVideoPlayer.so to ${RESOURCE_DIR}"
)
