cmake_minimum_required(VERSION 3.10.2)
project("ncx")

# Nombre de la librería nativa
add_library(
        ncx
        SHARED
        native-lib.cpp
        native_bridge.cpp)

add_executable(ffmem ffmem.c)

find_library(
        log-lib
        log)

target_link_libraries(
        ncx
        ${log-lib}
        android)
