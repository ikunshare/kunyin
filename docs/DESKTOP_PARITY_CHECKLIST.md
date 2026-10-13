# 桌面功能与验收清单

参考根目录：D:/Sources/kunyin-desktop；Android：D:/Sources/kunyin。
本表根据桌面路由、views 和 Android 实际入口重新盘点。存在代码不代表达到一致性验收；整体目标未完成。

当前证据说明：本次开始时测试源码目录缺失，历史 343 项测试无法在当前工作树重现；本次自动搜词/QQ 音乐云新增的 26 项测试可执行且通过，详见 [本轮验证](AUTO_LYRICS_QQC.md)。历史记录中的测试数量不等于当前回归覆盖。

| 范围 | 桌面依据 | Android 现状与下一步 | 完成证据要求 |
| --- | --- | --- | --- |
| 搜索四类结果 | views/SearchView.vue、stores/search.ts | 四类入口及请求代次、分页反馈已实现；QQ 音乐云在线歌曲搜索/详情补齐已验；搜索列表滚动降低折射开销，模拟器歌曲列表完成前后测量；其余来源/类型仍待验 | 同来源/类型/关键词的真实查询、重试和切换；各尺寸截图与滚动记录 |
| 自动搜词和 QQ 音乐云 | main/providers/kg/lyric.ts、main/providers/qqc/index.ts、main/ipc/handlers/player.ts | 原平台无正文时跨来源搜酷狗，转简体/前三候选/负缓存/共享取消已实现；qqc 歌曲复用 QQ 身份与后续路由；26 项测试、在线搜索/取词及 HTTP 测试音频设备链路通过 | 当前范围证据见 AUTO_LYRICS_QQC.md；授权真实 QQ 音频、各原平台在线差异和真机性能未在本轮验收 |
| 发现及榜单 | DiscoverView.vue、ChartsView.vue | 对应 Screen 与 ViewModel 存在，已有固定响应测试 | 来源/分类/排序、榜单分页、播放及加入歌单的运行证据 |
| 在线歌单 | PlaylistView.vue | 完整加载、收藏及错误重试有代码/测试 | 大歌单、取消/切换、收藏恢复和播放后续分页验收 |
| 本地歌单/收藏/最近/试听 | PlaylistsView.vue、LocalPlaylistView.vue | 排序、重复扫描、远端刷新、身份保护已逐项修改 | 全量 CRUD、排序与重复移除、跨来源歌曲和大列表性能运行证据 |
| 专辑与歌手 | AlbumView.vue、ArtistView.vue | 多碟、排序、多选、整专下载已实现；部分早期固定数据截图 | 当前 APK 的窄/宽屏全部标签、分页、下载与取消验收，旧截图不能证明新增行为 |
| 播放器与队列/歌词/MV/评论 | PlayerView.vue、相关 components/stores | 已有 Android 对应入口、队列/歌词等测试；未重新全面运行 | 实际音频、四类来源、分页队列、音质切换、歌词同步、评论和 MV 的运行记录 |
| 下载 | DownloadView.vue、SettingDownload.vue | 状态控制、多碟、标签补缺、文件提交与后台入队有测试 | 各格式真实可解码下载、原生/文档目录、暂停恢复及进程恢复、重复覆盖验证 |
| 歌曲信息 | SongInfoDialog.vue、utils/songIdentity.ts | 本轮补齐字段查看、复制文本/JSON，接入菜单与播放器 | 真实菜单/播放器弹窗、剪贴板、长字段与横竖屏运行验收 |
| 列表快捷按钮设置 | SettingList.vue、SongRow.vue | 已接入显示开关及八处列表播放/添加/下载快捷操作；开关已移至独立列表设置，并在 API35 预览入口验证窄/宽屏分类与开关状态；各歌曲列表操作仍待完整运行验收 | 开关持久化、所有歌曲列表适用、窄屏/宽屏和多选状态验证 |
| 网络代理设置 | SettingNetwork.vue | 已新增配置/端口探测/原生客户端接入，HTTP 与 WebSocket 均等待探测决定；WebSocket 排队/关闭及真实代理握手已测试；登录 WebView 已接入串行代理覆盖与等待/不支持反馈，配置和协调测试通过；HTTPS CONNECT、WebView 实际网络及设备运行待验 | 保存启用/主机/端口，接口/音频/封面/网页登录实际代理与不可达状态反馈；不能仅增加 UI 开关 |
| 外观/播放/歌词设置 | SettingBasic.vue、SettingPlay.vue、SettingPlayDetail.vue、SettingDesktopLyric.vue | 有外观、播放及悬浮歌词入口，尚需按每个桌面字段核对 | 设置字段映射、恢复/即时应用及各尺寸运行证据 |
| 备份/同步/其他/更新/关于 | SettingBackup.vue、SettingSync.vue、SettingOther.vue、SettingUpdate.vue、SettingAbout.vue | 备份已补齐可迁移设置及桌面映射；导出使用原子写入及最终内容校验，操作共享即时忙碌控制；歌曲库恢复/LX 导入已有事务、准确计数、酷狗 hash 去重及提交后刷新测试；主题重名跳过已修正，设置/主题文件/历史跨存储整体回滚与同步仍待验收 | 实际导出导入、异常原数据保留、跨端同步与更新/许可展示 |
| 快捷键/开发设置/窗口启动恢复 | SettingShortcuts.vue、SettingDeveloper.vue、router/index.ts | 尚需完整核对；不得把平台适配视为自动完成 | Android 可用的键盘/系统入口映射及启动恢复记录，明确记录不能证明一致的项目 |
| UI 完整性与整体流畅度 | 全部 routes/views/components | 搜索列表减少滚动时逐卡片折射，API35 软件 GPU 中位帧耗时 53ms→20ms；该局部调试测量不能证明全界面或真机流畅 | 页面和弹窗完整截图、窄/宽屏及状态恢复、滚动/转场帧率、无崩溃运行记录 |

详细修改和验证历史见 DESKTOP_PARITY.md。下一轮优先补 HTTPS CONNECT 和真实代理转发验证，并继续核对设置分类与列表操作运行体验；同时继续补设备上的 WebView 网络、UI 与流畅度验收。
