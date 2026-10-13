<p align="center"><a href="https://github.com/ikunshare/kunyin"><img width="200" src="https://raw.githubusercontent.com/ikunshare/kunyin/main/app/src/main/ic_launcher-playstore.png" alt="kunyin logo"></a></p>

<p align="center">
  <h1 align="center">坤音</h1>
</p>

<p align="center">
  <a href="https://github.com/ikunshare/kunyin/releases"><img src="https://img.shields.io/github/v/release/ikunshare/kunyin" alt="Release version"></a>
  <a href="https://github.com/ikunshare/kunyin/actions/workflows/android.yml"><img src="https://github.com/ikunshare/kunyin/actions/workflows/android.yml/badge.svg" alt="Build status"></a>
  <a href="https://github.com/ikunshare/kunyin/blob/main/LICENSE"><img src="https://img.shields.io/github/license/ikunshare/kunyin" alt="License"></a>
</p>

<p align="center">聚合多平台音源的 Android 音乐播放器</p>

## 说明

坤音（Kunyin）是一个基于 Kotlin + Jetpack Compose 开发的 Android 音乐播放器，聚合了多个在线音乐源，提供统一的搜索、播放、歌单与下载体验。

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

软件安装包请到 [Github Releases](https://github.com/ikunshare/kunyin/releases) 下载。

目前本项目的官方发布渠道只有 [Github Releases](https://github.com/ikunshare/kunyin/releases)，其他渠道均为第三方转载，与本项目无关。

## 关于后端服务

音源取链、卡密校验、公告、更新与默认同步服务器依赖作者自建的后端服务，该服务不在本仓库内开源。

## 致谢

- **[MPDL](https://github.com/MPDL-Official/MPDL)：项目整体代码来源，非开源项目**
- [music-lyric-kit](https://github.com/music-lyric/music-lyric-kit)、[music-lyric-player-web](https://github.com/music-lyric/music-lyric-player-web)：歌词解析管线与逐字歌词动画（MIT）
- [AMLL](https://github.com/Steve-xmh/applemusic-like-lyrics)：播放页流体背景渲染思路
- [LX Music](https://github.com/lyswhut/lx-music-desktop)：数据同步协议
- [Kyant0/backdrop](https://github.com/Kyant0/AndroidLiquidGlass)：液态玻璃效果
- [Lyricon](https://github.com/tomakino/lyricon)：状态栏歌词，仅引用SDK

## 免责声明

本项目仅供技术学习与交流使用，不提供任何音频文件的存储与分发能力。所有在线音源数据均从其公开接口拉取，本项目不对数据的合法性、准确性负责。

**请尊重版权，支持正版。** 使用本项目产生的任何直接或间接后果由使用者自行承担。完整条款见软件内的[使用协议](app/src/main/assets/eula.md)。

## 贡献

欢迎提交 Issue 与 PR。贡献前请先阅读「源码使用方法」搭建开发环境，并：

- 新增功能建议先开 Issue 说明，确认后再提交 PR；
- 修复 bug 的 PR 请附上复现方式与修复说明。

## 项目协议

本项目基于 [MIT License](./LICENSE) 开源，Copyright (c) 2026 ikunshare。
