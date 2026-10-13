package com.ikunshare.music.ui.screens.settings

import android.annotation.SuppressLint
import android.widget.Toast
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.ikunshare.music.R
import com.ikunshare.music.manager.CredentialManager
import com.ikunshare.music.platform.qq.QQCredentials
import com.ikunshare.music.platform.wy.WyCredentials
import com.ikunshare.music.utils.NetworkProxy
import com.ikunshare.music.utils.NetworkWebViewProxy
import com.ikunshare.music.utils.WebProxyPhase

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
        "qq" -> stringResource(R.string.web_login_qq)
        "wy" -> stringResource(R.string.web_login_wy)
        else -> ""
    }

    val context = LocalContext.current
    val coordinator = remember { NetworkWebViewProxy.coordinator }
    val proxyStatus by coordinator.status.collectAsState()
    val routingStatus by NetworkProxy.routing.status.collectAsState()
    val canLoad = proxyStatus.allowsLoad(routingStatus)
    var webView by remember(provider) { mutableStateOf<WebView?>(null) }
    var initialLoaded by remember(provider) { mutableStateOf(false) }
    var loading by remember(provider) { mutableStateOf(false) }
    var pageError by remember(provider) { mutableStateOf(false) }
    var rendererFailed by remember(provider) { mutableStateOf(false) }
    var resumeUrl by remember(provider) { mutableStateOf<String?>(null) }
    LaunchedEffect(canLoad, webView) {
        if (canLoad && !initialLoaded) webView?.let { initialLoaded = true; it.loadUrl(url) }
        else if (canLoad && resumeUrl != null) webView?.let { view ->
            val pending = resumeUrl!!; resumeUrl = null; view.loadUrl(pending)
        }
        else if (!canLoad) webView?.let { view ->
            if (loading) { resumeUrl = view.url ?: url; loading = false }
            view.stopLoading()
        }
    }

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
                    TextButton(enabled = canLoad && webView != null && !rendererFailed && !pageError, onClick = {
                        webView?.let { wv ->
                            val currentUrl = wv.url ?: url
                            val cookies = CookieManager.getInstance().getCookie(currentUrl)
                            val creds = extractCredentials(provider, CookieManager.getInstance().getCookie(url))
                                ?: extractCredentials(provider, cookies)
                            if (creds != null) {
                                credentialManager.saveCredential(provider, creds)
                                navController.popBackStack()
                            } else {
                                Toast.makeText(context, R.string.web_login_not_finished, Toast.LENGTH_SHORT).show()
                            }
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!rendererFailed) key(provider) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString =
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                    loading = true; pageError = false
                                }
                                override fun onPageFinished(view: WebView, url: String?) { loading = false }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) { loading = false; pageError = true }
                                }
                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                                    if (request.isForMainFrame) { loading = false; pageError = true }
                                }
                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    loading = false; rendererFailed = true; initialLoaded = false
                                    return true
                                }
                            }
                            webView = this
                        }
                    },
                    onRelease = { view ->
                        if (webView === view) webView = null
                        if (!rendererFailed) {
                            view.stopLoading()
                            view.webViewClient = WebViewClient()
                        }
                        view.destroy()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (loading && canLoad) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            if (!canLoad || pageError || rendererFailed) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.align(Alignment.Center).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        val waiting = !canLoad && proxyStatus.phase in listOf(WebProxyPhase.WAITING, WebProxyPhase.APPLYING, WebProxyPhase.READY)
                        if (waiting) CircularProgressIndicator()
                        Text(stringResource(when {
                            canLoad && (pageError || rendererFailed) -> R.string.web_login_load_failed
                            proxyStatus.phase == WebProxyPhase.UNSUPPORTED -> R.string.web_proxy_unsupported
                            proxyStatus.phase == WebProxyPhase.FAILED -> R.string.web_proxy_failed
                            else -> R.string.web_proxy_applying
                        }), modifier = Modifier.padding(16.dp))
                        if (!waiting) TextButton(onClick = {
                            if (!canLoad) coordinator.retry()
                            else if (pageError || rendererFailed) {
                                pageError = false
                                if (rendererFailed) rendererFailed = false else webView?.reload()
                            }
                        }) { Text(stringResource(R.string.retry)) }
                    }
                }
            } else if (proxyStatus.phase == WebProxyPhase.UNSUPPORTED) {
                Text(stringResource(R.string.web_proxy_system), Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun extractCredentials(
    provider: String,
    cookies: String?
): com.ikunshare.music.platform.base.ProviderCredentials? {
    if (cookies == null) return null
    return when (provider) {
        "qq" -> {
            val uin = (parseCookieValue(cookies, "wxuin") ?: parseCookieValue(
                cookies,
                "uin"
            ))?.removePrefix("o")
            val authst = parseCookieValue(cookies, "qm_keyst")
            if (!uin.isNullOrBlank() && !authst.isNullOrBlank()) QQCredentials(
                authst = authst,
                uin = uin,
                refreshToken = parseCookieValue(cookies, "psrf_qqrefresh_token") ?: "",
                accessToken = parseCookieValue(cookies, "psrf_qqaccess_token") ?: "",
                openid = parseCookieValue(cookies, "psrf_qqopenid") ?: ""
            ) else null
        }

        "wy" -> {
            val musicU = parseCookieValue(cookies, "MUSIC_U")
            if (!musicU.isNullOrBlank()) WyCredentials("MUSIC_U=$musicU") else null
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
