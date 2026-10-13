# 预测性返回与歌词页控件隐藏

2026-10-12，本轮按用户澄清的 Android 预测性返回手势实施。

## 实现

- Manifest 显式启用 `enableOnBackInvokedCallback`。当前 Navigation Compose 2.10.2 使用独立的 `predictivePopEnterTransition` / `predictivePopExitTransition`：根据手势起始边向对应方向平移、轻微缩小并淡出，上一页逐渐显现。进度、取消和提交由 NavHost 管理，普通按钮返回保留原有动画。
- 窄屏设置分类页的局部导航改用 `PredictiveBackHandler` 与 `SeekableTransitionState`；进度流结束才清除所选分类，取消后从仍存活的 Compose 作用域恢复动画，避免在已取消的手势协程里执行回弹。
- 设置处理器只在设置标签及 tabs 路由实际处于前台时启用，避免保活的设置页抢占其他页面返回。
- 播放控件仅在窄屏歌词页停稳后执行原有 4 秒自动隐藏；封面页、分页拖动中和宽屏双栏保持显示。切回封面立即恢复，重新进入歌词页重新计时；持续触摸、菜单和对话框仍暂停计时。隐藏后的首次点击仍只唤出控件。

API 依据：本地已解析依赖源码，以及 [Android 预测性返回文档](https://developer.android.com/develop/ui/compose/system/predictive-back-setup)。

## 验证

执行 `:app:assembleDebug :app:testDebugUnitTest :app:compileReleaseKotlin`，构建及当前 26 项现有回归测试通过；这些单测不是手势 UI 的验证依据。日志为 `build/predictive-back-final-validation.log`。

API35 / SwiftShader 模拟器运行最终 Debug APK，操作真实系统边缘返回与生产 UI：

| 场景 | 观察结果 | 本地证据（`app/build/ui-checks/`） |
| --- | --- | --- |
| 左侧专辑返回拖动、取消 | 画面随进度移动并预览搜索页；取消后专辑恢复，路由仍为 album，上一页仍为 tabs | `predictive-held.png`、`predictive-cancelled.png` |
| 右侧专辑返回拖动、提交 | 画面向左移动；提交后为 tabs，previousRoute 为 null，没有重复出栈 | `predictive-right.png`，调试导航报告 |
| 设置外观分类拖动、取消、提交 | 拖动预览分类列表；取消恢复外观页；再次提交返回分类列表 | `predictive-settings-progress.png`、`predictive-settings-cancelled.png`、`predictive-settings-completed.png` |
| 歌词页隐藏及首次唤醒 | 无操作后隐藏；首次点击显示播放按钮与两个进度条，仍暂停于 00:25 | `player-gesture-check-part2.json`、对应 XML、`player-firsttap-held.png` |
| 持续按住及松手 | 按住超过 5.2 秒仍显示；松手后重新计时并隐藏 | `player-gesture-check-part2.json` |
| 切回封面 | 等待超过 5.2 秒后播放按钮及两个进度条仍显示 | `player-gesture-check-part2.json` |
| 宽屏双栏 | 1280×800、160dpi 布局显示封面和歌词；等待后控件仍显示 | `player-wide-visible.png`、`player-wide-visible.json` |

可访问性查询的 idle 等待可能超过 4 秒，首次唤醒检查时在空白处保持按下以暂停计时；单独的 down 不会唤醒隐藏控件，因此仍能验证此前点击已唤醒且未跳转播放位置。早期未经预热的查询、旧层级文件及已撤回的按钮触摸反馈实验不作为最终验证依据。

原 1080×2400 软件 GPU 模拟器在旋转时退出；重启后以直接调整屏幕尺寸验证宽屏双栏，未将这次宿主退出判定为应用结果。宽屏运行的 crash buffer 为空。测试结束恢复原分辨率、密度、旋转及系统返回设置并关闭本轮模拟器。未操作连接的用户手机；旧版 Android、厂商系统手势及真机帧率未在本轮设备验证。

调试导航及固定歌曲夹具位于 debug 源集，正式包不包含。APK：`app/build/outputs/apk/debug/坤音-v26.6.7-debug.apk`。
