cmake_minimum_required(VERSION 3.22.1)

project(moontier_native_bridge LANGUAGES CXX)

add_library(moontier_native_bridge SHARED native_bridge.cpp)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_directories(moontier_native_bridge
    PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
)

target_link_libraries(moontier_native_bridge
    PRIVATE
        easytier_ffi
        ${log-lib}
        ${dl-lib}
)
