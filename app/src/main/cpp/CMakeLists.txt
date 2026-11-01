cmake_minimum_required(VERSION 3.22.1)

project("droidterm_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

add_library(
    droidterm_native
    SHARED
    pty/pty_bridge.cpp
    pty/process_manager.cpp
    pty/io_loop.cpp
    pty/terminal_resize.cpp
)

find_library(log-lib log)

target_link_libraries(
    droidterm_native
    ${log-lib}
)
