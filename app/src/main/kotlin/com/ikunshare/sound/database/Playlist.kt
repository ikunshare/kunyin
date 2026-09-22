package com.ikunshare.sound.database

data class Playlist(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val songCount: Int,
    val coverUrl: String?,
    val remoteSource: String? = null,
    val remoteId: String? = null,
    val autoRefresh: Boolean = false,
    val isSystem: Boolean = false,
    val systemKind: String? = null
)
