#!/usr/bin/env bash
# prepare_arm64_environment.sh
#
# 在 aarch64 Linux 上准备 Operit 的 arm64 原生 Android 构建环境。
# 详细背景与验证结果见 docs/doc-src/dev-core/ARM64_BUILD_SETUP.md。
#
# 用法：
#   bash ci/script/arm64_build/prepare_arm64_environment.sh [sdk_root]
#
# 默认 SDK 根目录为 /opt/android-sdk。脚本幂等，可重复执行。
#
# 完成内容：
#   1. 安装 SDK 组件（platform-tools、platforms;android-34、build-tools 34/35/36）
#   2. 下载 lzhiyong/termux-ndk 的 NDK r29 aarch64 并装入 <sdk>/ndk/29.0.14206865
#   3. 下载 lzhiyong/android-sdk-tools 的静态 aapt2/aidl/zipalign 并替换
#      build-tools 与 Gradle 缓存（Maven jar + transforms）中的 x86_64 二进制
#   4. 用系统 cmake/ninja 替换 SDK cmake;3.22.1 的二进制
#   5. 应用 terminal 子模块修复 patch（删除坏 libsudo.so + 声明 ndkVersion）
#   6. 确保 gradle.properties 包含 android.aapt2FromMaven=false

set -euo pipefail

SDK_ROOT="${1:-/opt/android-sdk}"
NDK_VERSION="29.0.14206865"
NDK_ARCHIVE_URL="https://github.com/Lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r29-aarch64.7z"
SDK_TOOLS_URL="https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

log() { printf '[arm64-build] %s\n' "$*"; }
die() { printf '[arm64-build] ERROR: %s\n' "$*" >&2; exit 1; }

# ---------- 前置检查 ----------
if [ "$(uname -m)" != "aarch64" ] && [ "$(uname -m)" != "arm64" ]; then
    die "本脚本仅用于 aarch64/arm64 Linux 宿主，当前架构: $(uname -m)"
fi
for tool in java curl unzip 7z cmake ninja python3; do
    command -v "$tool" >/dev/null 2>&1 || die "缺少系统工具: $tool（参见 ARM64_BUILD_SETUP.md 第 0 步）"
done
[ -d "$SDK_ROOT" ] || mkdir -p "$SDK_ROOT"

# ---------- 定位 sdkmanager ----------
SDKMANAGER=""
for candidate in \
    "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
    "$SDK_ROOT/cmdline-tools/tools/bin/sdkmanager"; do
    if [ -x "$candidate" ]; then SDKMANAGER="$candidate"; break; fi
done
if [ -z "$SDKMANAGER" ]; then
    log "未找到 sdkmanager，下载 cmdline-tools"
    CMDLINE_ZIP="$WORK_DIR/cmdline-tools.zip"
    curl -sL --retry 3 -o "$CMDLINE_ZIP" \
        "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
        || die "cmdline-tools 下载失败"
    mkdir -p "$SDK_ROOT/cmdline-tools"
    unzip -q "$CMDLINE_ZIP" -d "$SDK_ROOT/cmdline-tools"
    # 官方要求目录名为 latest
    [ -d "$SDK_ROOT/cmdline-tools/latest" ] || \
        mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
    SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
fi
log "sdkmanager: $SDKMANAGER"

# ---------- 1. SDK 组件 ----------
log "安装 SDK 组件"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" "platform-tools" "platforms;android-34" \
    "build-tools;34.0.0" "build-tools;35.0.0" "build-tools;36.0.0" \
    "cmake;3.22.1" >/dev/null 2>&1 || die "SDK 组件安装失败"
log "SDK 组件就绪"

# ---------- 2. NDK r29 aarch64 ----------
if [ -f "$SDK_ROOT/ndk/$NDK_VERSION/source.properties" ] && \
   [ -x "$SDK_ROOT/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]; then
    log "NDK $NDK_VERSION 已存在，跳过"
else
    log "下载 NDK r29 aarch64（约 350MB）"
    NDK_ARCHIVE="$WORK_DIR/ndk-r29-aarch64.7z"
    curl -sL --retry 3 -o "$NDK_ARCHIVE" "$NDK_ARCHIVE_URL" || die "NDK 下载失败"
    mkdir -p "$WORK_DIR/ndk-unpack"
    7z x -y -o"$WORK_DIR/ndk-unpack" "$NDK_ARCHIVE" >/dev/null || die "NDK 解压失败"
    NDK_SRC="$WORK_DIR/ndk-unpack/android-ndk-r29"
    [ -d "$NDK_SRC" ] || die "NDK 归档结构异常，缺少 android-ndk-r29 目录"
    rm -rf "$SDK_ROOT/ndk/$NDK_VERSION"
    cp -a "$NDK_SRC" "$SDK_ROOT/ndk/$NDK_VERSION"
    log "NDK $NDK_VERSION 安装完成"
fi
if ! "$SDK_ROOT/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --version >/dev/null 2>&1; then
    die "NDK clang 无法运行（不是静态 arm64 构建？）"
fi
log "NDK clang 验证通过"

# ---------- 3. 静态 aapt2/aidl/zipalign ----------
log "下载静态 arm64 build-tools"
SDK_TOOLS_ZIP="$WORK_DIR/sdk-tools-aarch64.zip"
curl -sL --retry 3 -o "$SDK_TOOLS_ZIP" "$SDK_TOOLS_URL" || die "android-sdk-tools 下载失败"
unzip -q "$SDK_TOOLS_ZIP" -d "$WORK_DIR/sdk-tools"
STATIC_BT="$WORK_DIR/sdk-tools/build-tools"
[ -x "$STATIC_BT/aapt2" ] || die "归档中缺少 build-tools/aapt2"
"$STATIC_BT/aapt2" version >/dev/null 2>&1 || die "静态 aapt2 无法运行"

for bt in 34.0.0 35.0.0 36.0.0; do
    BT_DIR="$SDK_ROOT/build-tools/$bt"
    [ -d "$BT_DIR" ] || die "缺少 build-tools $bt"
    for tool in aapt2 aidl zipalign; do
        cp -f "$STATIC_BT/$tool" "$BT_DIR/$tool"
        chmod +x "$BT_DIR/$tool"
    done
done
log "build-tools aapt2/aidl/zipalign 已替换为静态 arm64 版"

# 3b. 重打包 Gradle 缓存中的 Maven aapt2 jar（AGP 8.13 强制走 Maven 渠道）
# jar 路径 = <gradle cache>/modules-2/files-2.1/com.android.tools.build/aapt2/<版本>/<hash>/aapt2-<版本>-linux.jar
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
found_jar=0
while IFS= read -r aapt2_jar; do
    if python3 - "$aapt2_jar" "$STATIC_BT/aapt2" <<'PYEOF'
import sys, zipfile, io
jar, arm64_bin = sys.argv[1], sys.argv[2]
with open(arm64_bin, 'rb') as f:
    arm64 = f.read()
with zipfile.ZipFile(jar) as z:
    try:
        data = z.read('aapt2')
    except KeyError:
        sys.exit(0)
    if data == arm64:
        print('SKIP')
        sys.exit(0)
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w') as zo:
        for item in z.infolist():
            zo.writestr(item, arm64 if item.filename == 'aapt2' else z.read(item.filename))
with open(jar, 'wb') as f:
    f.write(out.getvalue())
print('REPACKED')
PYEOF
    then
        log "aapt2 maven jar 已处理: $aapt2_jar"
        found_jar=1
    else
        die "aapt2 maven jar 重打包失败: $aapt2_jar"
    fi
done < <(find "$GRADLE_HOME/caches/modules-2/files-2.1/com.android.tools.build/aapt2" \
    -name 'aapt2-*-linux.jar' -type f 2>/dev/null)

# 3c. 替换 transforms 缓存中已解压的 aapt2 二进制
find "$GRADLE_HOME/caches" -path '*transforms*' -path '*aapt2*' -name 'aapt2' -type f \
    -exec cp -f "$STATIC_BT/aapt2" {} \; -exec chmod +x {} \; 2>/dev/null || true

if [ "$found_jar" -eq 0 ]; then
    log "未发现 Maven aapt2 jar（首次构建时 AGP 会下载并走 transforms 替换路径）"
fi

# ---------- 4. SDK cmake/ninja 替换 ----------
CMAKE_BIN="$SDK_ROOT/cmake/3.22.1/bin"
[ -d "$CMAKE_BIN" ] || die "缺少 SDK cmake/3.22.1（sdkmanager 安装失败？）"
for tool in cmake cpack ctest; do
    rm -f "$CMAKE_BIN/$tool"
    ln -sf "$(command -v "$tool")" "$CMAKE_BIN/$tool"
done
rm -f "$CMAKE_BIN/ninja"
ln -sf "$(command -v ninja)" "$CMAKE_BIN/ninja"
log "SDK cmake/ninja 已指向系统版本（$("$CMAKE_BIN/cmake" --version | head -1)）"

# ---------- 5. terminal 子模块修复 patch ----------
TERMINAL_DIR="$REPO_ROOT/terminal"
PATCH_FILE="$REPO_ROOT/ci/script/arm64_build/patches/terminal-submodule-arm64.patch"
if [ -d "$TERMINAL_DIR/.git" ] && [ -f "$PATCH_FILE" ]; then
    if [ -f "$TERMINAL_DIR/src/main/jniLibs/arm64-v8a/libsudo.so" ]; then
        log "应用 terminal 子模块 patch（删除坏 libsudo.so + ndkVersion）"
        (cd "$TERMINAL_DIR" && git apply --index "$PATCH_FILE") \
            || die "terminal 子模块 patch 应用失败"
    else
        log "terminal 子模块 patch 已应用，跳过"
    fi
else
    log "跳过 terminal patch（子模块未初始化或 patch 不存在）"
fi

# ---------- 6. gradle.properties ----------
GRADLE_PROPS="$REPO_ROOT/gradle.properties"
if ! grep -q '^android.aapt2FromMaven=' "$GRADLE_PROPS"; then
    printf '\nandroid.aapt2FromMaven=false\n' >> "$GRADLE_PROPS"
    log "gradle.properties 已写入 android.aapt2FromMaven=false"
else
    log "gradle.properties 已配置，跳过"
fi

log "全部完成。接下来按 ARM64_BUILD_SETUP.md 第 2 步执行常规初始化后即可 ./gradlew assembleDebug"
