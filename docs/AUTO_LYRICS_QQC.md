# 自动搜词、QQ 音乐云与搜索滚动优化

2026-10-12。桌面参考目录为 `D:/Sources/kunyin-desktop`，本次目标为继续迁移自动搜词、QQ 音乐云，并优化流畅性。

## 范围核对

| 要求 | Android 实现 | 验证 |
| --- | --- | --- |
| 原平台优先、无正文时自动搜词 | `MusicRepository` 共用 `LyricRequests`；原平台异常、空白正文、仅翻译/音译时回退酷狗 | 单测覆盖原平台优先、异常/辅助轨回退；设备夹具走实际播放器和歌词页 |
| 搜词规则与桌面一致 | OpenCC tw2s 转换及 `歌手 - 歌名`；自动跨来源查询使用 man=yes，不附原平台 hash/audioId；酷狗原生使用 man=no | 桌面 opencc-js 生成的 14 组转换样本；签名参数、原生/手动标记及毫秒时长单测；繁体关键词在线查询 |
| 多主机、候选与解密 | 顺序尝试三个歌词主机，最多下载前三个有效候选；KRC 使用现有原生解密与歌词解析 | 主机异常重试、候选上限、无正文拒绝、显式候选单测；真实在线 KRC 解密/解析 |
| 缓存和取消 | fallback_v1 缓存正文及负结果；旧辅助轨缓存升级；显式候选另有身份；共享请求按消费者计数取消，清理后旧工作不能回填 | 并发共享、单个/最后消费者取消、清理竞态、候选身份及磁盘重启负缓存单测 |
| 播放器、歌词页和下载一致 | 三处均调用仓库，包括歌曲重定向；歌词解析在后台执行 | 代码入口核对；播放器/歌词页共享只取一次原平台；快速切歌晚到歌词不覆盖新曲单测 |
| QQ 音乐云搜索 | 注册 qqc；歌曲搜索分页 POST；批量 CgiGetTrackInfo 补齐信息，失败保留搜索字段和顺序 | 页码、批量去重、顺序、异常、占位、JSON 恢复单测；设备在线查询 5 首完整 QQ 歌曲 |
| QQ 歌曲后续路由 | 云结果 source=qqc、歌曲 type=qq；歌词、播放、封面、评论/专辑入口继续使用 QQ 身份；空音质占位可尝试普通 128k 播放 | 仓库路由和缓存失效单测；占位普通音质的 HTTP 测试音频实际播放 |
| 流畅性 | 歌词与云搜索支持实际网络取消；切换搜索来源保留已分来源的缓存；四类搜索列表滑动时暂缓逐卡片折射，停止 160ms 后恢复 | 网络等待响应头/响应体取消的本地套接字测试；来源切换缓存与偏好测试；搜索页同设备滚动前后测量 |

桌面依据为 `src/main/providers/kg/lyric.ts`、`src/main/ipc/handlers/player.ts`、`src/main/cache/lyricCache.ts` 和 `src/main/providers/qqc/index.ts`。QQ 音乐云只提供歌曲搜索，与桌面范围一致。Android 对云搜索失败保留错误重试反馈；详情失败仍展示占位结果。

## 当前可复现验证

运行 `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:compileReleaseKotlin --console=plain` 成功。本次新增并执行 26 项测试，失败/错误均为 0：LyricRequests 9、KgLyrics 5、转换 1、QqcProvider 5、CloudRepository/歌词切歌 3、可取消 HTTP 3。`git diff --check` 通过。日志为 `build/lyrics-cloud-final-validation.log`。

本次开始时 `app/src/test` 不存在；历史文档及旧构建报告中的 343 项测试不能作为当前工作树的回归证据。本次保留原有修改和历史记录，明确只报告当前可执行的 26 项测试。Release Kotlin 编译成功，不等于签名发布包或真机发布验收。

API35、1080×2400、密度 420、SwiftShader 模拟器安装 Debug APK。在线查询 QQ 音乐云返回 5 首歌曲、总计 807，补齐时长、音质、mid/mediaMid 等详情；繁体歌词关键词用 2 次酷狗请求取得逐字歌词，解析 71 行，请求均在后台。该在线结果为一次服务采样，服务可用性及耗时不作为长期保证。

生产 `SearchScreen` 的分页夹具实际从 20 行滚动到 60 行，点击进入生产 `PlayerScreen` / `PlayerViewModel`。合成 WAV 经本地 HTTP 服务交给真实 ExoPlayer 播放，报告 playing=true、positionMs=2785、quality=128k、error=null。播放器和歌词页合计原平台取词 1 次、酷狗请求 2 次、缓存 1 份；真实原生 KRC 解码器解析 15 行原创测试逐字歌词，截图确认显示与高亮。

夹具最初使用 file://，与当前 HTTP 播放通道不兼容，已改为 HTTP 并重验；早期暂停播放器截图及未真正导航的截图不作为成功证据。快速切歌词测试最初的一行英文包含歌名，被现有净化逻辑过滤；改用能解析的两行原创歌词后验证了新曲先返回、旧曲迟到的保护。

## 滚动测量

同一模拟器、相同尺寸/密度与 60 行测试结果，对搜索歌曲列表执行 12 次 `input swipe 650 2040 650 750 350`，用 `dumpsys gfxinfo com.ikunshare.music` 重置并采样。

| 指标 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 渲染帧数 | 174 | 275 |
| gfxinfo Janky frames | 129（74.14%） | 9（3.27%） |
| 帧耗时中位数 | 53ms | 20ms |
| P90 / P95 / P99 | 69 / 73 / 81ms | 32 / 34 / 48ms |
| legacy Janky frames | 96.55% | 93.09% |

软件 GPU、Debug 构建的 deadline 和 legacy 指标不同，帧数也随滑动速度变化；该结果证明此场景绘制开销下降，不代表稳定 60fps、真机帧率或全应用各页面已流畅验收。滑动期间保留卡片半透明底色、轮廓和选择态，静止后恢复折射。

本地证据在 `app/build/ui-checks/`：`migration-live-report.json`、`migration-live-final-report.json`、`migration-fixture-report.json`、`migration-fixture-final-report.json`、`migration-search-gfxinfo-before.txt`、`migration-search-gfxinfo-after.txt`、`migration-lyrics-visible.png`、`migration-final-lyrics.png` 及相应 XML。最终 APK 重装后再次在线搜索返回 5 首/总计 807、逐字歌词 71 行、2 次后台请求；再次点击搜索首曲播放，最终报告 playing=true、positionMs=3543、同样 1 次原平台/2 次酷狗请求，截图目视确认逐字高亮，crash 日志为空。探测入口和原创 KRC 夹具仅在 debug 源集中，正式包不包含。未操作已连接的用户手机。

本次所要求功能和搜索滚动优化已实施并完成上述验证。第三方真实音乐取流依赖既有解析服务/账号，本轮没有验证带授权的真实 QQ 音频；播放器设备验收使用合成测试音频。全应用桌面一致性、全部来源在线运行与真机流畅度仍按 [总清单](DESKTOP_PARITY_CHECKLIST.md) 跟进。
