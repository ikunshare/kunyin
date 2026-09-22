package com.ikunshare.sound.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikunshare.sound.R
import com.ikunshare.sound.ui.screens.player.SleepTimerAction
import com.ikunshare.sound.ui.screens.player.SleepTimerMode
import com.ikunshare.sound.ui.screens.player.SleepTimerState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepTimerDialog(
    timerState: SleepTimerState,
    onDismiss: () -> Unit,
    onSetTimer: (minutes: Int, finishCurrentSong: Boolean, action: SleepTimerAction) -> Unit,
    onSetTimerBySongs: (count: Int, finishCurrentSong: Boolean, action: SleepTimerAction) -> Unit,
    onCancelTimer: () -> Unit
) {
    if (timerState.isActive) {
        // 已有定时：显示状态和取消按钮
        ActiveTimerDialog(
            timerState = timerState,
            onDismiss = onDismiss,
            onCancelTimer = onCancelTimer
        )
    } else {
        // 设置新定时
        SetTimerDialog(
            onDismiss = onDismiss,
            onSetTimer = onSetTimer,
            onSetTimerBySongs = onSetTimerBySongs
        )
    }
}

@Composable
private fun ActiveTimerDialog(
    timerState: SleepTimerState,
    onDismiss: () -> Unit,
    onCancelTimer: () -> Unit
) {
    val statusText = when (timerState.mode) {
        SleepTimerMode.TIME if timerState.remainingMs > 0 -> {
            val totalSeconds = (timerState.remainingMs / 1000).toInt()
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            stringResource(R.string.sleep_timer_remaining_time, minutes, seconds)
        }

        SleepTimerMode.TIME if true -> {
            stringResource(R.string.sleep_timer_waiting_song_end)
        }

        SleepTimerMode.SONGS -> {
            stringResource(R.string.sleep_timer_remaining_songs, timerState.remainingSongs)
        }

        else -> ""
    }

    val actionText = when (timerState.action) {
        SleepTimerAction.PAUSE -> stringResource(R.string.sleep_timer_action_pause)
        SleepTimerAction.EXIT -> stringResource(R.string.sleep_timer_action_exit)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_timer_active)) },
        text = {
            Column {
                Text(statusText, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    actionText, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (timerState.finishCurrentSong) {
                    Text(
                        stringResource(R.string.sleep_timer_finish_current),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCancelTimer()
                onDismiss()
            }) {
                Text(
                    stringResource(R.string.sleep_timer_cancel),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.close),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetTimerDialog(
    onDismiss: () -> Unit,
    onSetTimer: (minutes: Int, finishCurrentSong: Boolean, action: SleepTimerAction) -> Unit,
    onSetTimerBySongs: (count: Int, finishCurrentSong: Boolean, action: SleepTimerAction) -> Unit
) {
    // 0 = 按时间, 1 = 按歌曲数
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedMinutes by remember { mutableIntStateOf(-1) }
    var selectedSongs by remember { mutableIntStateOf(-1) }
    var customInput by remember { mutableStateOf("") }
    var finishCurrentSong by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf(SleepTimerAction.PAUSE) }

    val timeOptions = listOf(10, 30, 60, 90, 120)
    val songOptions = listOf(5, 10, 20, 30, 50)

    val canConfirm = if (selectedTab == 0) {
        selectedMinutes > 0 || customInput.toIntOrNull()?.let { it > 0 } == true
    } else {
        selectedSongs > 0 || customInput.toIntOrNull()?.let { it > 0 } == true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_timer)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Tab 切换
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    TabLabel(
                        text = stringResource(R.string.sleep_timer_by_time),
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            customInput = ""
                            selectedSongs = -1
                        }
                    )
                    TabLabel(
                        text = stringResource(R.string.sleep_timer_by_songs),
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            customInput = ""
                            selectedMinutes = -1
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (selectedTab == 0) {
                    // 时间快速选择
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        timeOptions.forEach { minutes ->
                            ChipButton(
                                text = stringResource(R.string.sleep_timer_minutes, minutes),
                                selected = selectedMinutes == minutes && customInput.isEmpty(),
                                onClick = {
                                    selectedMinutes = minutes
                                    customInput = ""
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customInput,
                        onValueChange = {
                            customInput = it.filter { c -> c.isDigit() }
                            if (customInput.isNotEmpty()) selectedMinutes = -1
                        },
                        label = { Text(stringResource(R.string.sleep_timer_custom_minutes)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // 歌曲数快速选择
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        songOptions.forEach { count ->
                            ChipButton(
                                text = stringResource(R.string.sleep_timer_songs, count),
                                selected = selectedSongs == count && customInput.isEmpty(),
                                onClick = {
                                    selectedSongs = count
                                    customInput = ""
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customInput,
                        onValueChange = {
                            customInput = it.filter { c -> c.isDigit() }
                            if (customInput.isNotEmpty()) selectedSongs = -1
                        },
                        label = { Text(stringResource(R.string.sleep_timer_custom_songs)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 歌曲播完再停止
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { finishCurrentSong = !finishCurrentSong }
                ) {
                    Checkbox(
                        checked = finishCurrentSong,
                        onCheckedChange = { finishCurrentSong = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.tertiary
                        )
                    )
                    Text(
                        stringResource(R.string.sleep_timer_finish_current),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 停止动作选择
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { action = SleepTimerAction.PAUSE }
                ) {
                    RadioButton(
                        selected = action == SleepTimerAction.PAUSE,
                        onClick = { action = SleepTimerAction.PAUSE },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.tertiary
                        )
                    )
                    Text(
                        stringResource(R.string.sleep_timer_action_pause),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { action = SleepTimerAction.EXIT }
                ) {
                    RadioButton(
                        selected = action == SleepTimerAction.EXIT,
                        onClick = { action = SleepTimerAction.EXIT },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.tertiary
                        )
                    )
                    Text(
                        stringResource(R.string.sleep_timer_action_exit),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = if (customInput.isNotEmpty()) {
                        customInput.toIntOrNull() ?: 0
                    } else if (selectedTab == 0) {
                        selectedMinutes
                    } else {
                        selectedSongs
                    }
                    if (value > 0) {
                        if (selectedTab == 0) {
                            onSetTimer(value, finishCurrentSong, action)
                        } else {
                            onSetTimerBySongs(value, finishCurrentSong, action)
                        }
                        onDismiss()
                    }
                },
                enabled = canConfirm
            ) {
                Text(
                    stringResource(R.string.sleep_timer_set),
                    color = if (canConfirm) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.cancel),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    )
}

@Composable
private fun TabLabel(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(2.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.tertiary
                    else androidx.compose.ui.graphics.Color.Transparent
                )
        )
    }
}

@Composable
private fun ChipButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (selected) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
    else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (selected) MaterialTheme.colorScheme.tertiary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp
        )
    }
}
