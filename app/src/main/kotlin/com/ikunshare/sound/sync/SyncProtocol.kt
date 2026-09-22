package com.ikunshare.sound.sync

/** 与 Go 端 `sync/protocol.go` 对齐的常量。 */
object SyncProtocol {
    const val HELLO_MSG = "Hello~::^-^::~v4~"
    const val ID_PREFIX = "OjppZDo6"
    const val AUTH_MSG = "lx-music auth::"
    const val MSG_CONNECT = "lx-music connect"

    const val CLOSE_NORMAL = 1000
    const val CLOSE_FAILED = 4100

    const val FEATURE_LIST = "list"
    const val FEATURE_DISLIKE = "dislike"
    const val FEATURE_VERSION_LIST = "1"
    const val FEATURE_VERSION_DISLIKE = "1"

    const val COMPRESS_PREFIX = "cg_"
    const val COMPRESS_THRESH = 1024

    const val HEARTBEAT_SECS = 60L
    const val RPC_TIMEOUT_MS = 120_000L

    /** 客户端声明自己是 lx_music_mobile 协议版本；Go 端据此判断是否需要额外发送 "ping" 文本帧。 */
    const val CLIENT_KIND_MOBILE = "lx_music_mobile"
}
