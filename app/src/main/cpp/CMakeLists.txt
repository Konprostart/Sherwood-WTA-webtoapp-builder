# CMakeLists.txt for Sherwood WTA native components

cmake_minimum_required(VERSION 3.22.1)

project("webtoapp_native" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add compile options
# Note: do not use global add_compile_options, because node_bridge must export JNI symbols

# ==================== Node.js Bridge ====================
# JNI bridge layer: loads libnode.so via dlopen and calls node::Start()
add_library(
    node_bridge
    SHARED
    node_bridge.cpp
)

# Find the Android log library
find_library(
    log-lib
    log
)

find_library(
    dl-lib
    dl
)

# ★ 16KB page size alignment: Android 15+ devices use a 16KB page size,
# ELF LOAD segments must be aligned to a 16KB (16384) boundary, otherwise the app runs in compatibility mode

target_link_libraries(
    node_bridge
    ${log-lib}
)

# node_bridge compile options: keep JNI export symbols (do not use -fvisibility=hidden)
target_compile_options(node_bridge PRIVATE
    -ffunction-sections
    -fdata-sections
    -O2
    -DNDEBUG
)

# node_bridge uses gc-sections only, no strip (keeps JNI export symbols)
set_target_properties(node_bridge PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections -Wl,-z,max-page-size=16384"
)

# Standalone launcher used by the local build environment. It is packaged as an
# extracted asset and executed as a real `node` binary inside the app sandbox.
add_executable(
    node_launcher
    node_launcher.c
)

add_executable(
    go_exec_loader
    go_exec_loader.c
)

# User-mode exec loader for static ELFs (pmmp PHP) under SELinux W^X —
# host-preview only; a real JNI shared library, loaded lazily by
# StaticExecBridge when plain execve is unavailable. AArch64 only: the
# child trampoline is AArch64 asm, and the loader parses AArch64 ELFs.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    add_library(
        static_exec
        SHARED
        static_exec_loader.c
    )

    target_link_libraries(
        static_exec
        ${log-lib}
    )

    target_compile_options(static_exec PRIVATE
        -ffunction-sections
        -fdata-sections
        -O2
        -DNDEBUG
    )

    set_target_properties(static_exec PROPERTIES
        LINK_FLAGS "-Wl,--gc-sections -Wl,-z,max-page-size=16384"
    )
endif()

set_property(TARGET node_launcher PROPERTY C_STANDARD 11)

target_compile_options(node_launcher PRIVATE
    -ffunction-sections
    -fdata-sections
    -O2
    -DNDEBUG
)

target_link_libraries(
    node_launcher
    ${log-lib}
    ${dl-lib}
)

set_target_properties(node_launcher PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections -Wl,-z,max-page-size=16384"
)

set_property(TARGET go_exec_loader PROPERTY C_STANDARD 11)

target_compile_options(go_exec_loader PRIVATE
    -ffunction-sections
    -fdata-sections
    -O2
    -DNDEBUG
)

set_target_properties(go_exec_loader PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections -Wl,-z,max-page-size=16384"
)

# ==================== Performance Engine (extreme performance optimization engine) ====================
# C-level hot-path acceleration: URL parsing, MIME lookup, mmap I/O, performance JS injection
add_library(
    perf_engine
    SHARED
    perf_engine.c
)

set_property(TARGET perf_engine PROPERTY C_STANDARD 11)

# -O3 maximum speed optimization (the performance engine does not need size optimization)
target_compile_options(perf_engine PRIVATE
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -O3
    -DNDEBUG
    -Wall
    -Wextra
)

target_link_libraries(
    perf_engine
    ${log-lib}
)

set_target_properties(perf_engine PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections -Wl,--strip-all -Wl,-z,max-page-size=16384"
)

# ==================== System Optimizer (system-level extreme performance optimization) ====================
# C-level system tuning: CPU affinity, thread priority, OOM score, I/O readahead, temperature monitoring
add_library(
    sys_optimizer
    SHARED
    sys_optimizer.c
)

set_property(TARGET sys_optimizer PROPERTY C_STANDARD 11)

# -O3 maximum speed optimization
target_compile_options(sys_optimizer PRIVATE
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -O3
    -DNDEBUG
    -Wall
    -Wextra
)

target_link_libraries(
    sys_optimizer
    ${log-lib}
)

set_target_properties(sys_optimizer PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections -Wl,--strip-all -Wl,-z,max-page-size=16384"
)

# ==================== Browser Kernel (browser kernel spoofing engine) ====================
# Anti-WebView detection: comprehensive spoofing of navigator/chrome/WebGL/Permissions/Error stack
add_library(
    browser_kernel
    SHARED
    browser_kernel.c
)

set_property(TARGET browser_kernel PROPERTY C_STANDARD 11)

# -Os size optimization (this library is mostly static strings; no need for -O3 speed optimization)
target_compile_options(browser_kernel PRIVATE
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -Os
    -DNDEBUG
    -Wall
    -Wextra
)

target_link_libraries(
    browser_kernel
    ${log-lib}
)

set_target_properties(browser_kernel PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections -Wl,--strip-all -Wl,-z,max-page-size=16384"
)
