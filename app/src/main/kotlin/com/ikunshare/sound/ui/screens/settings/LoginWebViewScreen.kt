package com.ikunshare.sound.ui.screens.settings

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.ikunshare.sound.R
import com.ikunshare.sound.manager.CredentialManager
import com.ikunshare.sound.platform.qq.QQCredentials
import com.ikunshare.sound.platform.wy.WyCredentials

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginWebViewScreen(
    provider: String,
    credentialManager: CredentialManager,
    navController: NavController
) {
    val url = when (provider) {
        "qq" -> "https://y.qq.com"
        "wy" -> "https://music.163.com"
        else -> return
    }
    val title = when (provider) {
        "qq" -> "QQ音乐登录"
        "wy" -> "网易云登录"
        else -> ""
    }

    var webView by remember { mutableStateOf<WebView?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = {
                        webView?.let { wv ->
                            val currentUrl = wv.url ?: url
                            val cookies = CookieManager.getInstance().getCookie(currentUrl)
                            Log.d("LoginWebView", "完成登录 provider=$provider")
                            Log.d("LoginWebView", "当前URL: $currentUrl")
                            Log.d("LoginWebView", "Cookie: $cookies")
                            val creds = extractCredentials(provider, cookies)
                            Log.d(
                                "LoginWebView",
                                "提取凭据: ${if (creds != null) "成功" else "失败"}"
                            )
                            if (creds != null) {
                                credentialManager.saveCredential(provider, creds)
                            }
                            navController.popBackStack()
                        }
                    }) {
                        Text(
                            stringResource(R.string.finish_login),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                    webViewClient = WebViewClient()
                    loadUrl(url)
                    webView = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

private fun extractCredentials(
    provider: String,
    cookies: String?
): com.ikunshare.sound.platform.base.ProviderCredentials? {
    if (cookies == null) return null
    return when (provider) {
        "qq" -> {
            val uin = (parseCookieValue(cookies, "wxuin") ?: parseCookieValue(
                cookies,
                "uin"
            ))?.removePrefix("o")
            val authst = parseCookieValue(cookies, "qm_keyst")
            if (uin != null && authst != null) QQCredentials(
                authst = authst,
                uin = uin,
                refreshToken = parseCookieValue(cookies, "psrf_qqrefresh_token") ?: "",
                accessToken = parseCookieValue(cookies, "psrf_qqaccess_token") ?: "",
                openid = parseCookieValue(cookies, "psrf_qqopenid") ?: ""
            ) else null
        }

        "wy" -> {
            val musicU = parseCookieValue(cookies, "MUSIC_U")
            if (musicU != null) WyCredentials("MUSIC_U=$musicU") else null
        }

        else -> null
    }
}

private fun parseCookieValue(cookies: String, key: String): String? {
    return cookies.split(";")
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("=")
}
