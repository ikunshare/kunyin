package com.ikunshare.sound.lyric.kit.util

/**
 * 配置管理器（简化版）。
 *
 * 原库基于 lodash 的 dot-path 深合并与变更 diff 事件；在 Kotlin 移植中，各插件的配置均为不可变
 * data class，调用方传入完整配置对象（或使用 data class copy 覆盖部分字段），因此这里只需保存
 * 默认值与当前值，并在 apply/update 时通知监听者重建派生状态（如 Matcher）。
 */
class ConfigManager<C : Any>(
    private val default: C,
    private val onUpdate: ((C) -> Unit)? = null,
) {
    var current: C = default
        private set

    /** 重置为默认值后应用本次配置（等价于 reset + update）。 */
    fun apply(target: C?) {
        current = target ?: default
        onUpdate?.invoke(current)
    }

    /** 合并（此处等价于覆盖）配置。 */
    fun update(target: C?) {
        if (target == null) return
        current = target
        onUpdate?.invoke(current)
    }

    fun reset() {
        current = default
        onUpdate?.invoke(current)
    }
}
