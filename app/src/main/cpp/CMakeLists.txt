cmake_minimum_required(VERSION 3.10.2)
project("freezy_net")

# El nombre de la librería debe ser freezy_net
add_library(
        freezy_net
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
        freezy_net
        ${log-lib}
        android)
