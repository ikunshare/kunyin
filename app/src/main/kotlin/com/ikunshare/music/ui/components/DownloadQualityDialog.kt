package com.ikunshare.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ikunshare.music.R

private val allQualities = listOf(
    "master" to "臻品母带",
    "atmos_plus" to "臻品全景声 2.0",
    "atmos" to "臻品全景声",
    "hires" to "Hi-Res",
    "flac" to "无损 FLAC",
    "320k" to "高品 320kbps",
    "128k" to "标准 128kbps"
)

private val standardQualities = listOf(
    "hires" to "Hi-Res",
    "flac" to "无损 FLAC",
    "320k" to "高品 320kbps",
    "128k" to "标准 128kbps"
)

@Composable
fun DownloadQualityDialog(
    hideAi: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (qualityId: String) -> Unit,
    busy: Boolean = false
) {
    val options = if (hideAi) standardQualities else allQualities
    var selected by remember(hideAi) { mutableStateOf(options.first().first) }

    GlassAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.select_download_quality)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.quality_auto_downgrade),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                options.forEach { (id, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { selected = id }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == id,
                            enabled = !busy,
                            onClick = { selected = id },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.tertiary
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onConfirm(selected) }) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(if (busy) R.string.download_adding_tasks else R.string.download_btn),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(
                    stringResource(R.string.cancel),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    )
}
