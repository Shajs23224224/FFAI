cmake_minimum_required(VERSION 3.22.1)
project("ffai_native")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Compiler flags for optimization
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -ffast-math -fomit-frame-pointer -funroll-loops")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fvisibility=hidden -fvisibility-inlines-hidden")

# Architecture-specific optimizations
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+crc -mtune=cortex-a55")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv7-a -mtune=cortex-a55")
endif()

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# Gesture Engine Library
add_library(
    gesture_engine
    SHARED
    gesture/gesture_engine.cpp
    gesture/classifier.cpp
    gesture/executor.cpp
    gesture/interpolator.cpp
)

target_link_libraries(
    gesture_engine
    android
    log
    GLESv2
)

# Camera Engine Library
add_library(
    camera_engine
    SHARED
    camera/camera_controller.cpp
    camera/trajectory.cpp
    camera/pid_controller.cpp
)

target_link_libraries(
    camera_engine
    android
    log
    GLESv2
)

# Vision Processing Library
add_library(
    vision_processor
    SHARED
    vision/image_processor.cpp
    vision/jni_bridge.cpp
)

target_link_libraries(
    vision_processor
    android
    log
    jnigraphics
    GLESv2
)

# ML Inference Library
add_library(
    ml_inference
    SHARED
    ml/inference_engine.cpp
    ml/model_loader.cpp
)

target_link_libraries(
    ml_inference
    android
    log
)

# Utilities Library
add_library(
    ffai_utils
    SHARED
    utils/math_utils.cpp
    utils/timing.cpp
)

target_link_libraries(
    ffai_utils
    android
    log
)

# Main FFAI Native Library (combines all)
add_library(
    ffai_native
    SHARED
    ffai_native.cpp
)

target_link_libraries(
    ffai_native
    gesture_engine
    camera_engine
    vision_processor
    ml_inference
    ffai_utils
    android
    log
)

# Find and link OpenCV if available
find_package(OpenCV QUIET)
if(OpenCV_FOUND)
    target_link_libraries(vision_processor ${OpenCV_LIBS})
    include_directories(${OpenCV_INCLUDE_DIRS})
    add_definitions(-DUSE_OPENCV)
endif()
