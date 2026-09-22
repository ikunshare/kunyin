package com.ikunshare.sound.ui.theme

import com.google.gson.Gson

/**
 * 自定义主题颜色配置
 *
 * 所有颜色值以 ARGB Long 存储（0xAARRGGBB）。
 * 通过 Gson 序列化为 JSON 字符串持久化到 AppSettings。
 */
data class CustomThemeColors(
    // Material 基础色
    val primary: Long = 0xFF6366F1,
    val secondary: Long = 0xFF4338CA,
    val tertiary: Long = 0xFF22C55E,
    val background: Long = 0xFF0F0F23,
    val surface: Long = 0xFF1A1A2E,
    val surfaceVariant: Long = 0xFF2A2A3E,
    val onBackground: Long = 0xFFF8FAFC,
    val onSurfaceVariant: Long = 0xFF94A3B8,
    val error: Long = 0xFFEF4444,
    val outline: Long = 0xFF3F3F5A,

    // 高亮色
    // 注意：歌词颜色已从主题自定义中移除，统一由主题色派生并锁定，见 Theme.kt。
    val selectionHighlight: Long = 0xFF22C55E,     // 选择歌曲高亮遮罩

    // 底栏/搜索栏背景色（含透明度）
    val bottomBarColor: Long = 0xE61A1A2E,         // surface ~90% alpha

    // 歌曲卡片背景色（含透明度）
    val songCardColor: Long = 0xFF1A1A2E,           // surface

    // 弹窗背景色
    val dialogBackground: Long = 0xFF242438          // surfaceContainerHigh
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
