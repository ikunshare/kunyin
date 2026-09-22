package com.ikunshare.sound.manager

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.ikunshare.sound.platform.base.BaseProvider
import com.ikunshare.sound.platform.base.ProviderCredentials
import com.ikunshare.sound.platform.kg.KgCredentials
import com.ikunshare.sound.platform.kw.KwCredentials
import com.ikunshare.sound.platform.qq.QQCredentials
import com.ikunshare.sound.platform.wy.WyCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CredentialEntry(
    val provider: String,
    val credentials: ProviderCredentials?
)

class CredentialManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private val gson = Gson()
    private var providers: Map<String, BaseProvider>? = null

    private val _credentials = MutableStateFlow<Map<String, CredentialEntry>>(emptyMap())
    val credentials: StateFlow<Map<String, CredentialEntry>> = _credentials.asStateFlow()

    init {
        loadAll()
    }

    fun saveCredential(providerKey: String, creds: ProviderCredentials?) {
        val entry = CredentialEntry(providerKey, creds)
        val current = _credentials.value.toMutableMap()
        current[providerKey] = entry
        _credentials.value = current
        prefs.edit()
            .putString("cred_$providerKey", gson.toJson(creds))
            .apply()
        providers?.get(providerKey)?.credentials = creds
    }

    fun clearCredential(providerKey: String) {
        val current = _credentials.value.toMutableMap()
        current.remove(providerKey)
        _credentials.value = current
        prefs.edit().remove("cred_$providerKey").remove("usage_$providerKey").apply()
        providers?.get(providerKey)?.credentials = null
    }

    private fun loadAll() {
        val map = mutableMapOf<String, CredentialEntry>()
        for (key in listOf("qq", "wy", "kg", "kw")) {
            val json = prefs.getString("cred_$key", null) ?: continue
            val creds = deserializeCredentials(key, json) ?: continue
            map[key] = CredentialEntry(key, creds)
        }
        _credentials.value = map
    }

    fun applyToProviders(providers: Map<String, BaseProvider>) {
        this.providers = providers
        for ((key, entry) in _credentials.value) {
            providers[key]?.credentials = entry.credentials
        }
    }

    fun getProvider(key: String): BaseProvider? = providers?.get(key)

    private fun deserializeCredentials(key: String, json: String): ProviderCredentials? {
        return try {
            when (key) {
                "qq" -> gson.fromJson(json, QQCredentials::class.java)
                "wy" -> gson.fromJson(json, WyCredentials::class.java)
                "kg" -> gson.fromJson(json, KgCredentials::class.java)
                "kw" -> gson.fromJson(json, KwCredentials::class.java)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
