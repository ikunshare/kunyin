package com.ikunshare.music.ui.screens.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.ikunshare.music.ui.components.liquidGlass
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).semantics { heading() }
    )
}

@Composable
internal fun SettingSwitchItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .liquidGlass(RoundedRectangle(22.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 12.dp))
        SettingsSwitch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = { Box(Modifier.size(24.dp).liquidGlass(Capsule())) },
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.Transparent,
            uncheckedThumbColor = Color.Transparent,
            checkedTrackColor = MaterialTheme.colorScheme.tertiary,
            uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            uncheckedBorderColor = Color.Transparent
        )
    )
}

@Composable
internal fun SettingClickableItem(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp)
            .liquidGlass(RoundedRectangle(22.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (!value.isNullOrEmpty()) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) trailing()
        else if (onClick != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun SettingsDropdownRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    menu: @Composable () -> Unit
) {
    Box {
        SettingClickableItem(label = label, value = value,
            valueColor = MaterialTheme.colorScheme.tertiary, onClick = onClick)
        menu()
    }
}

// Keep Material's range, keyboard, accessibility and discrete-step behavior.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(
        activeTrackColor = MaterialTheme.colorScheme.tertiary,
        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    )
) {
    val interactionSource = remember { MutableInteractionSource() }
    val dragging by interactionSource.collectIsDraggedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val thumbScale by animateFloatAsState(
        targetValue = if (enabled && (dragging || pressed)) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f),
        label = "Settings slider thumb"
    )
    val scheme = MaterialTheme.colorScheme
    val thumbFill = remember(scheme.surfaceContainerHighest, scheme.surface) {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.4f), scheme.surfaceContainerHighest.copy(alpha = 0.2f))
        )
    }
    val thumbRim = remember(scheme.surface, scheme.onSurface) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.65f), scheme.onSurface.copy(alpha = 0.18f)))
    }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val activeColor = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor
    val inactiveColor = if (enabled) colors.inactiveTrackColor else colors.disabledInactiveTrackColor

    Slider(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interactionSource,
        // Only the small thumb refracts the scene. Keep the hit area generous
        // without adding another full-width glass card around the control.
        modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp).heightIn(min = 48.dp),
        thumb = {
            Box(
                Modifier.size(width = 40.dp, height = 26.dp)
                    .graphicsLayer {
                        scaleX = thumbScale
                        scaleY = thumbScale
                        alpha = if (enabled) 1f else 0.45f
                    }
                    .clip(Capsule())
                    .liquidGlass(Capsule())
                    .background(thumbFill, Capsule())
                    .border(1.dp, thumbRim, Capsule())
            )
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val span = state.valueRange.endInclusive - state.valueRange.start
                val fraction = if (span > 0f) {
                    ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
                } else 0f
                val activeWidth = size.width * fraction
                val corner = CornerRadius(size.height / 2f)
                drawRoundRect(inactiveColor, cornerRadius = corner)
                if (activeWidth > 0f) {
                    drawRoundRect(
                        color = activeColor,
                        topLeft = Offset(if (isRtl) size.width - activeWidth else 0f, 0f),
                        size = Size(activeWidth, size.height),
                        cornerRadius = corner
                    )
                }
            }
        }
    )
}
