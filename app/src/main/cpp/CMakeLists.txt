cmake_minimum_required(VERSION 3.22.1)

project(edge_detection)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find OpenCV
set(OpenCV_DIR "C:/OpenCV-android-sdk/sdk/native/jni")
find_package(OpenCV REQUIRED)

# ======== Main library: edge detection + RTSP decoder ========
add_library(edge_detection SHARED
        edge_detection.cpp
        rtsp_decoder.cpp)

target_link_libraries(edge_detection
        ${OpenCV_LIBS}
        android
        log
        c++_shared
        GLESv2
        EGL)

# ======== VIT Tracker library ========
add_library(vit_tracker SHARED
        vit_tracker/vit_tracker.cpp
        vit_tracker/vit_jni_bridge.cpp)

target_include_directories(vit_tracker PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${OpenCV_INCLUDE_DIRS})

target_link_libraries(vit_tracker
        ${OpenCV_LIBS}
        android
        log
        c++_shared
        GLESv2
        EGL)

message(STATUS "OpenCV found: ${OpenCV_FOUND}")
message(STATUS "OpenCV version: ${OpenCV_VERSION}")
message(STATUS "Building for ABI: ${ANDROID_ABI}")
