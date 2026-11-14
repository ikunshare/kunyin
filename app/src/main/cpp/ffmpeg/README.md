# FFmpeg（仅 ALAC 解码器）

这里只保留解码 ALAC 需要的最小 ffmpeg：`libavcodec` 的 `alac` 解码器 + `libavutil`。
四个 ABI 的静态库已经预编译好放在 `lib/<abi>/`，构建 APK 时无需再编译 ffmpeg。

- 版本：ffmpeg 7.1.1
- 许可：LGPL-2.1（见 `LICENSE`），未启用任何 GPL 组件
- 最小 API：24（与 `minSdk` 一致）

## 为什么自带解码器

Android 没有系统 ALAC 解码器，`MediaCodec.createDecoderByType("audio/alac")` 会抛
`NAME_NOT_FOUND`。旧实现用的是 Apple 参考解码器（Apache-2.0），现已换成 ffmpeg：
编码侧（桌面版）本来就用 ffmpeg，两边解码行为一致，也免去了自己维护一份解码器。

## 重新构建

```bash
# 1. 下载并解压源码
curl -LO https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz && tar -xf ffmpeg-7.1.1.tar.xz

# 2. 交叉编译四个 ABI（需要 Linux 环境与 Android NDK r28）
ANDROID_NDK=/path/to/android-ndk-r28b ./build-alac.sh ./ffmpeg-7.1.1 ./out

# 3. 覆盖进项目
cp -r ./out/*/lib/* ./lib/ && cp -r ./out/arm64-v8a/include ./include
```

配置开关在 `build-alac.sh` 里：`--disable-everything --enable-decoder=alac`，其余全部关闭
（无 demuxer / protocol / filter / 网络），只留 ARM NEON 汇编加速以减小体积。

## 解码接口约定

ffmpeg 的 alac 解码器要求 `extradata` 是 36 字节的 QuickTime 原子头：

| 偏移 | 长度 | 内容 |
| --- | --- | --- |
| 0 | 4 | 原子长度（36，大端） |
| 4 | 4 | `"alac"` |
| 8 | 4 | 版本（0） |
| 12 | 4 | 每包帧数（大端） |
| 16 | 24 | ALACSpecificConfig |

输入输出：

- 输入：单个 ALAC 包（每包一个 `send_packet`）。
- 输出：平面格式的 `AVFrame`（16 位为 `S16P`，20/24/32 位为 `S32P`），
  20 位已左移 12 位、24 位已左移 8 位。JNI 层统一交错化并转成小端，
  20/24 位按 24 位容器输出，与 FLAC 编码路径的位深一致。

## 逐字节验证

`alac_verify.cpp` + `extract_packets.py` 用于把 m4a 里的 ALAC 包抽出来，
用同一份 `AlacDecoderCore` 解码，再和 `ffmpeg -f s16le` 的输出做 `cmp`。
16 位与 24 位素材都已验证逐字节一致。
