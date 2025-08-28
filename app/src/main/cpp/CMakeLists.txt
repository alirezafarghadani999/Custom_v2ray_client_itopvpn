cmake_minimum_required(VERSION 3.22.1)
project(hevtunnel_jni)

add_library(hevtunnel_jni SHARED hevtunnel_jni.cpp)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(hevtunnel_jni
        ${log-lib}
        ${dl-lib}
)
