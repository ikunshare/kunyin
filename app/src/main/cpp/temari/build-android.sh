#!/usr/bin/env bash
set -euo pipefail
export RUSTUP_NO_SELF_UPDATE=1

ROOT=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$ROOT/../../../../.." && pwd)
NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
HOST=${ANDROID_NDK_HOST:-windows-x86_64}
if [[ -z "$NDK" ]]; then
    echo "Set ANDROID_NDK_HOME to the Android NDK directory" >&2
    exit 1
fi

TARGETS=(
    "arm64-v8a:aarch64-linux-android:aarch64-linux-android24-clang.cmd"
    "armeabi-v7a:armv7-linux-androideabi:armv7a-linux-androideabi24-clang.cmd"
    "x86:i686-linux-android:i686-linux-android24-clang.cmd"
    "x86_64:x86_64-linux-android:x86_64-linux-android24-clang.cmd"
)

export CARGO_TARGET_DIR="$PROJECT_ROOT/build/temari-android"
for item in "${TARGETS[@]}"; do
    IFS=: read -r abi target linker <<< "$item"
    normalized=$(printf '%s' "$target" | tr 'a-z-' 'A-Z_')
    variable="CARGO_TARGET_${normalized}_LINKER"
    export "$variable"="$NDK/toolchains/llvm/prebuilt/$HOST/bin/$linker"
    cargo build --manifest-path "$ROOT/Cargo.toml" --target "$target" --release --locked
    mkdir -p "$ROOT/../../jniLibs/$abi"
    cp "$CARGO_TARGET_DIR/$target/release/libtemari.so" "$ROOT/../../jniLibs/$abi/libtemari.so"
done
