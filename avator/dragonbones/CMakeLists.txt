cmake_minimum_required(VERSION 3.22.1)

project("dragonbones_native")

include("${CMAKE_CURRENT_LIST_DIR}/../../cmake/operit_git_source.cmake")

# The module relocation removed ignored vendored sources, so clean builds must
# populate the same runtime snapshot through the repository's archive helper.
operit_prepare_git_source(
    OPERIT_DRAGONBONES_CPP_SOURCE_DIR
    OPERIT_DRAGONBONES_CPP_BINARY_DIR
    dragonbones_cpp
    "https://github.com/DragonBones/DragonBonesCPP.git"
    "f696143045e07b7c4c4590bdd95534e9e8074b37"
)

# DragonBonesCPP 自带的老版 rapidjson 的 GenericStringRef::operator= 会给 const 成员
# 赋值，新 clang（NDK r29 的 clang 21）按 C++17 语义直接报错，上游至今未修复。
# 该 operator= 无任何调用方，删除即可；rapidjson 上游在后续版本中已移除。
# 使用幂等替换，每次 CMake 重新配置都会重新应用（FetchContent 会覆盖源码目录）。
set(DRAGONBONES_RAPIDJSON_HEADER "${OPERIT_DRAGONBONES_CPP_SOURCE_DIR}/3rdParty/rapidjson/document.h")
file(READ "${DRAGONBONES_RAPIDJSON_HEADER}" DRAGONBONES_RAPIDJSON_CONTENT)
string(REPLACE
    "    GenericStringRef& operator=(const GenericStringRef& rhs) { s = rhs.s; length = rhs.length; }\n"
    ""
    DRAGONBONES_RAPIDJSON_CONTENT
    "${DRAGONBONES_RAPIDJSON_CONTENT}"
)
file(WRITE "${DRAGONBONES_RAPIDJSON_HEADER}" "${DRAGONBONES_RAPIDJSON_CONTENT}")

set(DRAGONBONES_RUNTIME_ROOT "${OPERIT_DRAGONBONES_CPP_SOURCE_DIR}/DragonBones/src")
set(DRAGONBONES_RAPIDJSON_ROOT "${OPERIT_DRAGONBONES_CPP_SOURCE_DIR}/3rdParty")
set(DRAGONBONES_ANDROID_CPP_ROOT "${OPERIT_DRAGONBONES_CPP_SOURCE_DIR}/AndroidCompose/src/dragonBones/cpp")

# Gather all C++ source files from the dragonbones library
file(GLOB_RECURSE DRAGONBONES_SOURCES
    "${DRAGONBONES_RUNTIME_ROOT}/dragonBones/animation/*.cpp"
    "${DRAGONBONES_RUNTIME_ROOT}/dragonBones/armature/*.cpp"
    "${DRAGONBONES_RUNTIME_ROOT}/dragonBones/core/*.cpp"
    "${DRAGONBONES_RUNTIME_ROOT}/dragonBones/event/*.cpp"
    "${DRAGONBONES_RUNTIME_ROOT}/dragonBones/factory/*.cpp"
    "${DRAGONBONES_RUNTIME_ROOT}/dragonBones/geom/*.cpp"
    "${DRAGONBONES_RUNTIME_ROOT}/dragonBones/model/*.cpp"
    "${DRAGONBONES_RUNTIME_ROOT}/dragonBones/parser/*.cpp"
    "cpp/opengl/OpenGLSlot.cpp"
    "cpp/opengl/OpenGLFactory.cpp"
)

add_library(
    dragonbones_native
    SHARED
    "cpp/JniBridge.cpp"
    ${DRAGONBONES_SOURCES}
)

target_include_directories(dragonbones_native PRIVATE
    "cpp"
    "${DRAGONBONES_RUNTIME_ROOT}"
    "${DRAGONBONES_RAPIDJSON_ROOT}"
    "${DRAGONBONES_ANDROID_CPP_ROOT}"
)

# Link against required Android libraries
find_library(log-lib log)
find_library(GLESv2-lib GLESv2)
find_library(android-lib android)

target_link_libraries(
    dragonbones_native
    ${log-lib}
    ${GLESv2-lib}
    ${android-lib}
)

# 添加 16KB 页面大小支持（Android 15+ 要求）
target_link_options(dragonbones_native PRIVATE "-Wl,-z,max-page-size=16384")
