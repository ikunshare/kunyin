#!/usr/bin/env bash
# 为 Android 四个 ABI 构建只含 ALAC 解码器的 ffmpeg 静态库。
#
# 用法：
#   1. 解压 ffmpeg 源码，例如 ffmpeg-7.1.1
#   2. ANDROID_NDK=<ndk 根目录> ./build-alac.sh <ffmpeg 源码目录> <输出目录>
#
# 产物：<输出目录>/<abi>/lib/libavcodec.a、libavutil.a 以及 include 头文件，
# 直接覆盖到 app/src/main/cpp/ffmpeg/{lib,include} 即可。
set -euo pipefail

FFMPEG_DIR="${1:?用法: build-alac.sh <ffmpeg 源码目录> <输出目录>}"
OUT_ROOT="${2:?用法: build-alac.sh <ffmpeg 源码目录> <输出目录>}"
NDK="${ANDROID_NDK:?请设置 ANDROID_NDK}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=24

# 只保留 ALAC 解码所需的最小集合：体积优先，同时保留 NEON 汇编加速。
CONFIGURE_FLAGS=(
    --disable-everything
    --enable-decoder=alac
    --enable-avcodec --enable-avutil
    --enable-static --disable-shared
    --disable-programs --disable-doc --disable-debug
    --disable-network --disable-autodetect
    --disable-avdevice --disable-avformat --disable-avfilter
    --disable-swscale --disable-swresample --disable-postproc
    --disable-encoders --disable-muxers --disable-demuxers --disable-parsers
    --disable-bsfs --disable-protocols --disable-indevs --disable-outdevs
    --disable-filters --disable-hwaccels --disable-iconv --disable-vulkan
    --disable-symver --enable-pic --enable-small
    --disable-programs --disable-zlib --disable-bzlib --disable-lzma
    --disable-sdl2 --disable-xlib --disable-libdrm --disable-vaapi
    --disable-vdpau --disable-cuda --disable-cuvid --disable-ffnvcodec
    --disable-amf --disable-mediacodec --disable-videotoolbox
)

# abi -> ffmpeg arch / clang 目标三元组
build_abi() {
    local abi="$1" arch="$2" triple="$3" cpu="$4" extra="${5:-}"
    local build_dir="$OUT_ROOT/build-$abi"
    local prefix="$OUT_ROOT/$abi"

    if [ -f "$prefix/lib/libavcodec.a" ]; then
        echo "==> $abi 已存在，跳过"
        return
    fi

    echo "==> 配置 $abi ($arch / $triple)"
    rm -rf "$build_dir"
    mkdir -p "$build_dir" "$prefix"
    cd "$build_dir"
    "$FFMPEG_DIR/configure" \
        "${CONFIGURE_FLAGS[@]}" \
        --prefix="$prefix" \
        --target-os=android \
        --arch="$arch" \
        --cpu="$cpu" \
        --enable-cross-compile \
        --cc="$TOOLCHAIN/${triple}${API}-clang" \
        --cxx="$TOOLCHAIN/${triple}${API}-clang++" \
        --ar="$TOOLCHAIN/llvm-ar" \
        --ranlib="$TOOLCHAIN/llvm-ranlib" \
        --strip="$TOOLCHAIN/llvm-strip" \
        --nm="$TOOLCHAIN/llvm-nm" \
        --sysroot="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot" \
        $extra \
        > configure.log 2>&1 || { tail -40 configure.log; return 1; }

    echo "==> 编译 $abi（并行 $(( $(nproc) > 8 ? 8 : $(nproc) ))）"
    make -j"$(( $(nproc) > 8 ? 8 : $(nproc) ))" > make.log 2>&1 || { tail -40 make.log; return 1; }
    make install > install.log 2>&1 || { tail -40 install.log; return 1; }
    # 去掉 install 里用不上的工具产物，只留静态库与头文件。
    rm -rf "$prefix/share" "$prefix/lib/pkgconfig"
    echo "==> $abi 完成: $(du -sh "$prefix/lib" | cut -f1)"
    cd "$OUT_ROOT"
}

build_abi arm64-v8a   aarch64  aarch64-linux-android        armv8-a
build_abi armeabi-v7a arm      armv7a-linux-androideabi     armv7-a
build_abi x86_64      x86_64   x86_64-linux-android         x86-64  "--disable-x86asm"
build_abi x86         x86      i686-linux-android           i686    "--disable-x86asm"

echo "全部完成：$OUT_ROOT"
