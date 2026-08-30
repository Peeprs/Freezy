cmake_minimum_required(VERSION 3.10.2)
project("freezy_public")

add_library(
    freezy_public
    SHARED
    native_bridge.cpp
    network_engine.cpp
)

target_compile_options(freezy_public PRIVATE
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

target_link_options(freezy_public PRIVATE
    -Wl,--gc-sections
)

find_library(log-lib log)

target_link_libraries(freezy_public
    ${log-lib}
    android
)
