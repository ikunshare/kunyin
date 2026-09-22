package com.ikunshare.sound.platform.qq

enum class QRStatus { WAITING, SCANNED, CONFIRMED, TIMEOUT, REFUSED, ERROR }


data class QRPollResult(
    val status: QRStatus,
    val message: String,
    val credentials: QQCredentials? = null
)
