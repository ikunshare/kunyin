package com.ikunshare.sound.model

enum class PlayMode(val label: String) {
    SEQUENTIAL("顺序播放"),
    LIST_LOOP("列表循环"),
    SHUFFLE("随机播放"),
    SINGLE_LOOP("单曲循环"),
    SINGLE_PLAY("单曲播放");

    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]
}
