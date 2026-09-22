package com.ikunshare.sound.ui.screens.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ikunshare.sound.R
import com.ikunshare.sound.manager.CredentialManager
import com.ikunshare.sound.platform.qq.MobileQRLogin
import com.ikunshare.sound.platform.qq.QRStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.WebSocket
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRLoginScreen(
    credentialManager: CredentialManager,
    navController: NavController
) {
    var qrImage by remember { mutableStateOf<ByteArray?>(null) }
    var statusText by remember { mutableStateOf("") }
    var qrStatus by remember { mutableStateOf<QRStatus?>(null) }
    var retryTrigger by remember { mutableIntStateOf(0) }

    val webSockets = remember { mutableListOf<WebSocket>() }

    // 登录成功后保存凭据并返回
    LaunchedEffect(qrStatus) {
        if (qrStatus == QRStatus.CONFIRMED) {
            delay(800.milliseconds)
            navController.popBackStack()
        }
    }

    // 获取二维码 + 监听
    LaunchedEffect(retryTrigger) {
        qrImage = null
        qrStatus = null
        statusText = ""

        withContext(Dispatchers.IO) {
            try {
                val (imageBytes, qrcodeId) = MobileQRLogin.createQRCode()
                qrImage = imageBytes

                val ws = MobileQRLogin.connectAndListen(
                    qrcodeId,
                    onStatus = { result ->
                        statusText = result.message
                        qrStatus = result.status
                        if (result.status == QRStatus.CONFIRMED && result.credentials != null) {
                            credentialManager.saveCredential("qq", result.credentials)
                        }
                    },
                    onWebSocketChanged = { newWs -> webSockets.add(newWs) }
                )
                webSockets.add(ws)
            } catch (e: Exception) {
                statusText = "获取二维码失败: ${e.message}"
                qrStatus = QRStatus.ERROR
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webSockets.forEach { it.close(1000, "disposed") }
            webSockets.clear()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QQ音乐扫码登录") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 二维码
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                val imageData = qrImage
                if (imageData != null) {
                    val bitmap = remember(imageData) {
                        BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    // 过期/失败遮罩
                    if (qrStatus == QRStatus.TIMEOUT || qrStatus == QRStatus.ERROR || qrStatus == QRStatus.REFUSED) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                            contentAlignment = Alignment.Center
                        ) {
                            TextButton(onClick = {
                                webSockets.forEach { it.close(1000, "retry") }
                                webSockets.clear()
                                retryTrigger++
                            }) {
                                Text(
                                    stringResource(R.string.qr_retry),
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // 状态文字
            Text(
                text = when (qrStatus) {
                    null -> stringResource(R.string.qr_loading)
                    QRStatus.WAITING -> stringResource(R.string.qr_scan_hint)
                    QRStatus.SCANNED -> stringResource(R.string.qr_status_scanned)
                    QRStatus.CONFIRMED -> stringResource(R.string.qr_status_success)
                    QRStatus.TIMEOUT -> stringResource(R.string.qr_status_timeout)
                    QRStatus.REFUSED -> stringResource(R.string.qr_status_refused)
                    QRStatus.ERROR -> statusText
                },
                style = MaterialTheme.typography.bodyLarge,
                color = when (qrStatus) {
                    QRStatus.CONFIRMED -> MaterialTheme.colorScheme.tertiary
                    QRStatus.ERROR, QRStatus.TIMEOUT, QRStatus.REFUSED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center
            )
        }
    }
}
