cmake_minimum_required(VERSION 3.22.1)
project("ffai_native")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Compiler flags
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -ffast-math")

# Main FFAI Native Library (minimal)
add_library(
    ffai_native
    SHARED
    ffai_native.cpp
)

target_link_libraries(
    ffai_native
    android
    log
)
