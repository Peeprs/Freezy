cmake_minimum_required(VERSION 3.10.2)
project("ncx")

# Nombre de la librería nativa
add_library(
        ncx
        SHARED
        native-lib.cpp
        input_manager.cpp
        recoil_engine.cpp
        native_bridge.cpp
        input_listener.cpp)

find_library(
        log-lib
        log)

target_link_libraries(
        ncx
        ${log-lib}
        android)
