# Operit 在 Linux ARM64（aarch64）上的原生构建指南

本指南补充 [BUILDING.md](./BUILDING.md)：在 **aarch64 Linux**（如 PRoot Ubuntu、ARM 服务器、Apple Silicon 虚拟机）上，
**完全使用 arm64 原生工具链**构建 Operit Android APK，不依赖 qemu、box64 或任何跨架构模拟。

> 官方流程在 x86_64 环境照旧使用 BUILDING.md，本指南只解决 ARM64 宿主机无法运行官方 x86_64 工具链的问题。

## 为什么需要本指南

Android 构建的两类关键工具官方只发布 x86_64 Linux 宿主：

| 工具 | 官方情况（截至 2026-08） |
|------|--------------------------|
| aapt2 | Google Maven `com.android.tools.build:aapt2` 仅有 `linux`(x86_64)/`osx`/`windows`，最新 9.4.0-alpha08 仍未提供 `linux-aarch64` |
| NDK 工具链 | 官方 NDK 所有版本仅有 `linux-x86_64` 宿主；本项目 7 个 CMake 原生模块（dragonbones/terminal/mnn/llama/mmd/fbx/quickjs）必须使用 NDK clang |
| SDK cmake/ninja | `cmake;3.22.1` 包内的二进制为 x86_64 |
| build-tools aidl/zipalign | x86_64（aapt2 另有 Maven 渠道，见下） |

## 工具链来源（均已验证）

| 组件 | 来源 | 说明 |
|------|------|------|
| NDK r29 aarch64 | [lzhiyong/termux-ndk](https://github.com/Lzhiyong/termux-ndk) Release `android-ndk` 的 `android-ndk-r29-aarch64.7z`（824 stars） | LLVM 从 AOSP llvm-toolchain 构建（与官方同源），**静态 musl 链接**，可在任意 Linux（glibc/bionic/musl）运行；clang 21.0.0 |
| aapt2/aidl/zipalign | [lzhiyong/android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools) Release `35.0.2` 的 `android-sdk-tools-static-aarch64.zip`（645 stars） | **静态链接** aarch64 版 aapt2 2.19（对应 build-tools 35.0.2） |
| cmake/ninja | 系统 apt 包 | cmake >= 3.22.1、ninja，直接 symlink 进 SDK cmake 目录 |
| 其余（d8/apksigner/sdkmanager） | 官方 | 纯 Java 实现，架构无关 |

项目声明的 NDK 版本为 **29.0.14206865**（r29，AGP 8.13.2 默认要求 r27，但上游源码未锁版本，
本方案显式声明 r29 以使用 arm64 工具链；NDK r29 内置 clang 18/21 均验证可编译本项目的 C++17 代码）。

## 初始化步骤

### 0. 系统依赖

```bash
sudo apt update
sudo apt install -y git wget curl unzip p7zip-full openjdk-21-jdk nodejs npm python3 gcc g++ cmake ninja-build
sudo npm install -g pnpm
```

### 1. 一键环境准备脚本

仓库提供 `ci/script/arm64_build/prepare_arm64_environment.sh`，完成：

1. 安装 SDK 组件（platforms;android-34、build-tools;34.0.0/35.0.0/36.0.0、platform-tools）
2. 下载并安装 NDK r29 aarch64 到 `<sdk>/ndk/29.0.14206865`
3. 下载静态 aapt2/aidl/zipalign 并替换 build-tools 与 Gradle 缓存中的 x86_64 版本
4. 用系统 cmake/ninja symlink 替换 SDK `cmake/3.22.1` 的二进制
5. 应用 `terminal` 子模块修复 patch（删除坏 libsudo.so + 声明 ndkVersion）
6. 写入 `gradle.properties` 的 `android.aapt2FromMaven=false`

```bash
# 用法：SDK 根目录可选，默认 /opt/android-sdk
bash ci/script/arm64_build/prepare_arm64_environment.sh /opt/android-sdk
```

脚本幂等：重复执行会跳过已完成的步骤，可随时重跑修复半途中断。

### 2. 常规初始化（与官方一致）

```bash
# 三份依赖归档（Google Drive，官方 CI 脚本）
RUNNER_TEMP=/tmp bash ci/script/download_android_dependencies.sh full /tmp/operit_deps
python3 ci/script/prepare_android_dependencies.py --profile full --archives /tmp/operit_deps --repository .

# 前端与示例包
npm install && npm --prefix web-chat install && npm run build:webchat
python3 ./tools/example_packages/sync_example_packages.py

# local.properties 指向 SDK
echo 'sdk.dir=/opt/android-sdk' > local.properties
```

### 3. 构建

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 已知问题与修复（已合入本分支）

构建过程中发现并修复了 4 个上游/仓库问题，均为根因修复，不是绕过：

### 1. MNN 上游：qwen3_tts_demo 无条件 include audio/audio.hpp

`qwen3_tts_demo.cpp` 无条件 `#include "audio/audio.hpp"`，但 include 路径仅在
`MNN_BUILD_AUDIO=ON` 时提供（默认 OFF），导致 clang 编译报 `'audio/audio.hpp' file not found`。
上游在 `MNN_LLM_BUILD_DEMO` 默认 ON 的情况下无条件构建该 demo，属上游不一致。

修复：`llm/mnn/CMakeLists.txt` 关闭 `MNN_LLM_BUILD_DEMO`（命令行 demo，App 不需要，
与项目既有的 TEST/TOOLS 裁剪同风格）。

### 2. DragonBonesCPP 老版 rapidjson 与 clang 21 不兼容

`3rdParty/rapidjson/document.h` 的 `GenericStringRef::operator=` 给 `const` 成员赋值，
clang 21 按 C++17 语义直接报错（`cannot assign to non-static data member 'length' with const-qualified type`）。
上游至今未修复，且该 operator= 无任何调用方。

修复：`avator/dragonbones/CMakeLists.txt` 在 FetchContent 之后做**幂等 patch**（string REPLACE 删除该行）。

> **踩坑**：不要直接改 `.cxx/operit_deps` 下 FetchContent 拉取的源码——CMake 每次重新配置都会
> 重新下载 archive 并覆盖源码目录，手工修改会丢失。必须在项目 CMakeLists.txt 里做可重放的 patch。

### 3. dragonbones 缺少 abiFilters

其他原生模块都限定 `arm64-v8a`，唯独 dragonbones 没有，会浪费编译 armeabi-v7a/x86 等
无关 ABI（app 只打包 arm64-v8a）。

修复：补齐 `ndk { abiFilters.addAll(listOf("arm64-v8a")) }`。

### 4. terminal 子模块 vendored 的 libsudo.so 是 2 字节坏文件

`terminal/src/main/jniLibs/arm64-v8a/libsudo.so` 内容仅为 `$@`（2 字节），
导致 llvm-strip 报 `not recognized as a valid object file`。
查证 libsu v6.0.0 的 core 已无任何 native 依赖（无 `System.loadLibrary` 调用），
该文件是旧版 libsu 的残留。

修复：删除该文件。子模块改动以 patch 形式保存在
`ci/script/arm64_build/patches/terminal-submodule-arm64.patch`（子模块无法随主仓库提交，
如需推送到 OperitTerminalCore 上游请单独进行）。

## 踩坑记录（环境侧）

- **esbuild postinstall 被拦截**：npm 11 默认拦截未审批的 install 脚本，esbuild 的 arm64
  原生二进制不会自动下载，Vite 构建会失败。需手动 `cd node_modules/esbuild && node install.js`
  （根目录与 web-chat 各一次）。
- **Maven aapt2 daemon 启动失败**：AGP 8.13 强制从 Maven 下载 aapt2（`android.aapt2FromMaven=false`
  已不再生效），daemon 因 x86_64 二进制无法启动。处理：重打包 Gradle 缓存中的
  `aapt2-8.13.2-14304508-linux.jar`（把 jar 内 `aapt2` 替换为静态 arm64 版），并替换
  `caches/8.13/transforms/` 下已解压的二进制（脚本已自动化）。
- **AGP 自动安装默认 NDK**：项目源码未声明 `ndkVersion` 时，AGP 8.13.2 会自动下载
  `ndk;27.0.12077973`（x86_64）。本方案在全部 8 个有 CMake 的模块显式声明
  `ndkVersion = "29.0.14206865"`，同时 SDK 中保留 r29 目录。
- **MNN 需要宿主机 gcc/g++**：MNN schema 生成阶段用宿主机编译器构建 flatc（项目 CMake
  已处理），arm64 宿主直接用系统 gcc 即可。
- **STT 模型资产自动下载**：首次构建会按 `app/config/stt-model-assets.properties` 从
  Hugging Face 下载模型并校验 SHA-256，属正常行为。

## 验证结果

在 aarch64 PRoot Ubuntu 24.04（Android 15 设备）上实测：

```
BUILD SUCCESSFUL in 17m 1s
app-debug.apk 约 436 MB，含 44 个 arm64-v8a native 库
```

全部 7 个原生模块（dragonbones/terminal/mnn/llama/mmd/fbx/quickjs）编译通过，
APK zip 结构校验完整。
