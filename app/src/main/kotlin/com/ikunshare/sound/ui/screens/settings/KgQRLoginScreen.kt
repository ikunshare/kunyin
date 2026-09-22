package com.ikunshare.sound.ui.screens.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.ikunshare.sound.R
import com.ikunshare.sound.manager.CredentialManager
import com.ikunshare.sound.platform.kg.KgCredentials
import com.ikunshare.sound.platform.kg.KgThirdsso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("UseKtx")
private fun generateQRBitmap(content: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bitmap = createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap[x, y] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    return bitmap
}

private enum class KgQRStatus { LOADING, WAITING, SUCCESS, EXPIRED, ERROR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KgQRLoginScreen(
    credentialManager: CredentialManager,
    navController: NavController
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf(KgQRStatus.LOADING) }
    var errorMsg by remember { mutableStateOf("") }
    var retryTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryTrigger) {
        qrBitmap = null
        status = KgQRStatus.LOADING
        errorMsg = ""

        withContext(Dispatchers.IO) {
            try {
                val creds = KgCredentials(userid = "anonymous", token = "password")
                KgThirdsso.registerDevice(creds)

                val qrCode = KgThirdsso.getQRCode(creds)
                if (qrCode == null) {
                    status = KgQRStatus.ERROR
                    errorMsg = "获取二维码失败"
                    return@withContext
                }

                qrBitmap = generateQRBitmap(qrCode.url)
                status = KgQRStatus.WAITING

                var attempts = 0
                while (isActive && attempts < 90) {
                    delay(2000.milliseconds)
                    attempts++
                    val result = KgThirdsso.pollQRAuth(creds, qrCode.ticket)
                    if (result != null) {
                        credentialManager.saveCredential("kg", result)
                        status = KgQRStatus.SUCCESS
                        return@withContext
                    }
                }
                if (status == KgQRStatus.WAITING) {
                    status = KgQRStatus.EXPIRED
                }
            } catch (e: Exception) {
                status = KgQRStatus.ERROR
                errorMsg = "错误: ${e.message}"
            }
        }
    }

    LaunchedEffect(status) {
        if (status == KgQRStatus.SUCCESS) {
            delay(800.milliseconds)
            navController.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("酷狗音乐扫码登录") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
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
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = qrBitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        contentScale = ContentScale.Fit
                    )
                    if (status == KgQRStatus.EXPIRED || status == KgQRStatus.ERROR) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            TextButton(onClick = { retryTrigger++ }) {
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

            Text(
                text = when (status) {
                    KgQRStatus.LOADING -> stringResource(R.string.qr_loading)
                    KgQRStatus.WAITING -> "请用酷狗音乐App扫描二维码"
                    KgQRStatus.SUCCESS -> stringResource(R.string.qr_status_success)
                    KgQRStatus.EXPIRED -> stringResource(R.string.qr_status_timeout)
                    KgQRStatus.ERROR -> errorMsg
                },
                style = MaterialTheme.typography.bodyLarge,
                color = when (status) {
                    KgQRStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
                    KgQRStatus.ERROR, KgQRStatus.EXPIRED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center
            )
        }
    }
}
