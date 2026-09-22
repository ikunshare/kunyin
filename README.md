<p align="center"><a href="https://github.com/ikunshare/kunyin"><img width="200" src="https://raw.githubusercontent.com/ikunshare/kunyin/main/app/src/main/ic_launcher-playstore.png" alt="kunyin logo"></a></p>

<p align="center">
  <h1 align="center">坤音 KunYin</h1>
</p>

<p align="center">
  <a href="https://github.com/ikunshare/kunyin/releases"><img src="https://img.shields.io/github/v/release/ikunshare/kunyin" alt="Release version"></a>
  <a href="https://github.com/ikunshare/kunyin/actions/workflows/android.yml"><img src="https://github.com/ikunshare/kunyin/actions/workflows/android.yml/badge.svg" alt="Build status"></a>
  <a href="https://github.com/ikunshare/kunyin/blob/main/LICENSE"><img src="https://img.shields.io/github/license/ikunshare/kunyin" alt="License"></a>
</p>

<p align="center">聚合多平台音源的 Android 音乐播放器</p>

## 说明

坤音（KunYin）是一个基于 Kotlin + Jetpack Compose 开发的 Android 音乐播放器，聚合了多个在线音乐源，提供统一的搜索、播放、歌单与下载体验。

本项目代码为纯AI生成，介意不要使用。

所用技术栈：

- Kotlin / Jetpack Compose（Material 3）
- Media3 ExoPlayer
- OkHttp / Coil
- C++（NDK，歌词解析与音频解密）

运行要求：Android 7.0（API 24）及以上。

已支持的在线音源：

- 网易云音乐（`wy`）
- QQ 音乐（`qq`）
- 酷狗音乐（`kg`）
- 酷我音乐（`kw`）
- JOOX（`joox`）

> 桌面端项目：<https://github.com/ikunshare/kunyin-desktop>

## 主要功能

- 多音源聚合搜索（单曲 / 专辑 / 歌手）
- 在线歌单 / 专辑 / 歌手页，登录后的「我的歌单」与每日推荐
- 多档音质播放与下载（128K / 320K / 无损 / Hi-Res / 臻品等），下载自动写入标签、封面与歌词
- 歌词展示：逐行 / 逐字 / 翻译 / 音译，桌面歌词悬浮窗，支持 Lyricon 与魅族状态栏歌词
- Apple Music 风格播放页与封面取色流体背景
- 本地音乐、收藏（我喜欢）、自建歌单
- 卡密激活、平台账号登录（QQ / 网易云 / 酷狗）
- LX 数据同步（多端歌单 / 收藏同步）、LX 歌单导入
- 备份与恢复、多主题、字号 / 字重自定义

## 下载

软件安装包请到 [GitHub Releases](https://github.com/ikunshare/kunyin/releases) 下载。

目前本项目的官方发布渠道只有 [GitHub Releases](https://github.com/ikunshare/kunyin/releases)，其他渠道均为第三方转载，与本项目无关。

## 源码使用方法

### 环境要求

- Android Studio（或命令行 Android SDK），`compileSdk` 37
- JDK 17+（Gradle 会按 `gradle/gradle-daemon-jvm.properties` 自动下载 JDK 21 工具链）
- NDK `28.2.13676358`、CMake `3.22.1`

```bash
sdkmanager "ndk;28.2.13676358" "cmake;3.22.1"
```

### 构建 Debug 包

```bash
./gradlew assembleDebug
```

### 构建 Release 包

Release 签名通过 Gradle 属性传入，四项需同时提供，全部缺省时输出未签名的 Release 包：

```bash
./gradlew assembleRelease \
  -PKEYSTORE_STORE_FILE=release.jks \
  -PKEYSTORE_KEY_ALIAS=<alias> \
  -PKEYSTORE_PASSWORD=<store password> \
  -PKEYSTORE_KEY_PASSWORD=<key password>
```

`KEYSTORE_STORE_FILE` 为相对仓库根目录的路径。构建产物输出在 `app/build/outputs/apk/` 目录。

## 发布流程

[Android CI 工作流](.github/workflows/android.yml) 在推送到 `main` 时构建 Debug 包，推送 `v` 前缀标签时构建签名的 Release 包并发布到 GitHub Releases。

1. 修改 `app/build.gradle.kts` 中的 `verName` 与 `verCode`（`verCode` 必须递增，客户端据此判断是否有新版本）。
2. 在 `LOG.md` 中新增 `## v<verName>` 小节，其内容会作为 Release 说明与软件内的更新日志。
3. 提交改动并打上与 `verName` 一致的标签：

   ```bash
   git add .
   git commit -m "Release: v26.6.7"
   git tag v26.6.7
   git push origin main --tags
   ```

Release 签名需要在仓库 Secrets 中配置：

| Secret | 说明 |
| --- | --- |
| `KEYSTORE_STORE_FILE_BASE64` | keystore 文件的 Base64 |
| `KEYSTORE_STORE_FILE` | keystore 文件名（如 `release.jks`） |
| `KEYSTORE_KEY_ALIAS` | 密钥别名 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEYSTORE_KEY_PASSWORD` | 密钥密码 |

未配置时（例如 fork 仓库）会跳过签名。

## 软件内更新

每个 Release 会附带 CI 生成的 `update.json`（版本号、APK 文件名、SHA-256、更新日志）。客户端从 `https://github.com/ikunshare/kunyin/releases/latest/download/update.json` 获取，不调用 GitHub API，因此不受未认证 API 的限流影响。

针对中国大陆网络，客户端会同时请求 GitHub 直连与若干 GitHub 加速镜像（见 `UpdateChecker.MIRROR_PREFIXES`），采用最先返回的线路，并优先用该线路下载 APK，失败时依次切换其余线路。下载完成后会校验 SHA-256，系统安装时也会校验签名与已安装版本一致。

如需强制更新，可在 `update.json` 中加入 `minVersionCode`，低于该值的客户端将无法跳过更新。

## 关于后端服务

音源取链、卡密校验、公告与默认同步服务器依赖作者自建的后端服务，该服务不在本仓库内开源。

## 致谢

- [music-lyric-kit](https://github.com/music-lyric/music-lyric-kit)、[music-lyric-player-web](https://github.com/music-lyric/music-lyric-player-web)：歌词解析管线与逐字歌词动画（MIT）
- [AMLL](https://github.com/Steve-xmh/applemusic-like-lyrics)：播放页流体背景渲染思路
- [LX Music](https://github.com/lyswhut/lx-music-desktop)：数据同步协议
- [Kyant0/backdrop](https://github.com/Kyant0/AndroidLiquidGlass)：液态玻璃效果
- [Lyricon](https://github.com/tomakino/lyricon)：状态栏歌词

## 免责声明

本项目仅供技术学习与交流使用，不提供任何音频文件的存储与分发能力。所有在线音源数据均从其公开接口拉取，本项目不对数据的合法性、准确性负责。

**请尊重版权，支持正版。** 使用本项目产生的任何直接或间接后果由使用者自行承担。完整条款见软件内的[使用协议](app/src/main/assets/eula.md)。

## 贡献

欢迎提交 Issue 与 PR。贡献前请先阅读「源码使用方法」搭建开发环境，并：

- 新增功能建议先开 Issue 说明，确认后再提交 PR；
- 修复 bug 的 PR 请附上复现方式与修复说明。

## 项目协议

本项目基于 [MIT License](./LICENSE) 开源，Copyright (c) 2026 ikunshare。
