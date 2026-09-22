package com.ikunshare.sound.utils.crypto

object KgCrypto {
    init {
        System.loadLibrary("KgCrypto")
    }

    external fun getKgIdentity(body: String): String
    external fun getSignature(body: String): String
    external fun getTrialSignature(body: String): String
}
