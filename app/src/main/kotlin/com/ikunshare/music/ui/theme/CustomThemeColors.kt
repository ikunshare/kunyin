package com.ikunshare.music.ui.theme

import com.google.gson.Gson

/**
 * 自定义主题颜色配置
 *
 * 所有颜色值以 ARGB Long 存储（0xAARRGGBB）。
 * 通过 Gson 序列化为 JSON 字符串持久化到 AppSettings。
 */
data class CustomThemeColors(
    // Material 基础色
    val primary: Long = 0xFFFF8A4C,
    val secondary: Long = 0xFFFFC978,
    val tertiary: Long = 0xFFFFB437,
    val background: Long = 0xFF0C0C0E,
    val surface: Long = 0xFF151518,
    val surfaceVariant: Long = 0xFF212126,
    val onBackground: Long = 0xFFF4F4F6,
    val onSurfaceVariant: Long = 0xFF9C9CA6,
    val error: Long = 0xFFFF5A5F,
    val outline: Long = 0xFF35353C,

    // 高亮色
    // 注意：歌词颜色已从主题自定义中移除，统一由主题色派生并锁定，见 Theme.kt。
    val selectionHighlight: Long = 0xFFFFB437,     // 选择歌曲高亮遮罩

    // 底栏/搜索栏背景色（含透明度）
    val bottomBarColor: Long = 0xE6151518,         // surface ~90% alpha

    // 歌曲卡片背景色（含透明度）
    val songCardColor: Long = 0xFF151518,           // surface

    // 弹窗背景色
    val dialogBackground: Long = 0xFF1F1F24          // surfaceContainerHigh
) {
    companion object {
        private val gson = Gson()

        fun fromJson(json: String): CustomThemeColors? {
            return try {
                if (json.isBlank()) null
                else gson.fromJson(json, CustomThemeColors::class.java)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun toJson(): String = gson.toJson(this)
}
