package com.ikunshare.music.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.RoundedRectangle

/*
 * Liquid Glass for dialogs.
 *
 * Dialogs live in their own window, so they cannot sample a backdrop recorded in the activity
 * composition the way in-window glass does. GlassDialogHostBox records the whole app window into
 * a layer while at least one glass dialog is showing; glassDialogSurface then draws that layer
 * behind the dialog using screen coordinates, so the same frosted refraction appears on top of
 * whatever is on screen (tabs, detail pages or the player).
 *
 * Below Android 12 there is no RenderEffect blur, so the surface falls back to a translucent
 * tinted panel with the same rim; nothing is sampled across windows there.
 */

val DialogCornerRadius: Dp = 28.dp

@Stable
class GlassDialogHost internal constructor(internal val layer: LayerBackdrop) {
    internal var hostCoordinates: LayoutCoordinates? by mutableStateOf(null)
    internal var activeDialogs by mutableIntStateOf(0)
}

val LocalGlassDialogHost = staticCompositionLocalOf<GlassDialogHost?> { null }

@Composable
fun rememberGlassDialogHost(): GlassDialogHost {
    val layer = rememberLayerBackdrop()
    return remember(layer) { GlassDialogHost(layer) }
}

private val crossWindowGlassSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Wraps the whole app window; recording only happens while a glass dialog is up. */
@Composable
fun GlassDialogHostBox(host: GlassDialogHost, content: @Composable () -> Unit) {
    val recording = host.activeDialogs > 0 && crossWindowGlassSupported
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { host.hostCoordinates = it }
            .then(if (recording) Modifier.layerBackdrop(host.layer) else Modifier)
    ) {
        content()
    }
}

/** Draws the app window layer from another window, aligning the two by screen position. */
private class CrossWindowBackdrop(private val host: GlassDialogHost) : Backdrop {
    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val own = coordinates?.takeIf { it.isAttached } ?: return
        val hostCoordinates = host.hostCoordinates?.takeIf { it.isAttached } ?: return
        val offset = own.positionOnScreen() - hostCoordinates.positionOnScreen()
        translate(-offset.x, -offset.y) { drawLayer(host.layer.graphicsLayer) }
    }
}

/** Registers a showing dialog with the host so the window layer gets recorded. */
@Composable
private fun TrackGlassDialog() {
    val host = LocalGlassDialogHost.current ?: return
    DisposableEffect(host) {
        host.activeDialogs++
        onDispose { host.activeDialogs-- }
    }
}

/** The system scrim is softened: the glass already darkens what it covers. */
@Composable
private fun SoftenDialogDim(amount: Float = 0.32f) {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.setDimAmount(amount)
    }
}

/**
 * Glass panel for dialog content. Replaces `Surface(shape, color = surfaceContainerHigh)` in
 * custom dialogs; pair it with [GlassDialog] or [GlassAlertDialog].
 */
@Composable
fun Modifier.glassDialogSurface(
    cornerRadius: Dp = DialogCornerRadius,
    tint: Color = Color.Unspecified
): Modifier {
    val host = LocalGlassDialogHost.current
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val rim = remember(dark) { glassRim(dark) }
    val shape = RoundedRectangle(cornerRadius)
    return if (host != null && crossWindowGlassSupported) {
        val surface = if (tint.isSpecified) tint else colors.surface.copy(alpha = if (dark) 0.64f else 0.72f)
        val backdrop = remember(host) { CrossWindowBackdrop(host) }
        drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(28.dp.toPx())
                lens(14.dp.toPx(), 22.dp.toPx())
            },
            shadow = null,
            highlight = null,
            onDrawSurface = { drawRect(surface) }
        ).border(0.7.dp, rim, shape)
    } else {
        val surface = if (tint.isSpecified) tint.copy(alpha = maxOf(tint.alpha, 0.94f)) else colors.surfaceContainerHigh
        clip(shape).background(surface).border(0.7.dp, rim, shape)
    }
}

/** Drop-in for `Dialog` whose content draws itself with [glassDialogSurface]. */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    TrackGlassDialog()
    val contentColor = MaterialTheme.colorScheme.onSurface
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        SoftenDialogDim()
        // A Surface used to host dialog content and set this; the glass panel is a plain Box.
        CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
    }
}

/** Ready-made glass panel: [GlassDialog] plus a padded [Column] on a [glassDialogSurface]. */
@Composable
fun GlassDialogPanel(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    cornerRadius: Dp = DialogCornerRadius,
    contentPadding: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassDialog(onDismissRequest = onDismissRequest, properties = properties) {
        Column(
            modifier.fillMaxWidth().glassDialogSurface(cornerRadius).padding(contentPadding),
            content = content
        )
    }
}

/**
 * Drop-in replacement for Material 3 `AlertDialog` with the same slots and layout metrics,
 * drawn on Liquid Glass. `containerColor` becomes the glass tint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    cornerRadius: Dp = DialogCornerRadius,
    containerColor: Color = Color.Unspecified,
    iconContentColor: Color = MaterialTheme.colorScheme.tertiary,
    titleContentColor: Color = MaterialTheme.colorScheme.onSurface,
    textContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    properties: DialogProperties = DialogProperties()
) {
    TrackGlassDialog()
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier, properties = properties) {
        SoftenDialogDim()
        Column(
            Modifier
                .sizeIn(minWidth = 280.dp, maxWidth = 560.dp)
                .glassDialogSurface(cornerRadius, containerColor)
                .padding(24.dp)
        ) {
            icon?.let {
                CompositionLocalProvider(LocalContentColor provides iconContentColor) {
                    Box(Modifier.padding(bottom = 16.dp).align(Alignment.CenterHorizontally)) { it() }
                }
            }
            title?.let {
                CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                        Box(
                            Modifier
                                .padding(bottom = 16.dp)
                                .align(if (icon == null) Alignment.Start else Alignment.CenterHorizontally)
                        ) { it() }
                    }
                }
            }
            text?.let {
                CompositionLocalProvider(LocalContentColor provides textContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        Box(
                            Modifier
                                .weight(weight = 1f, fill = false)
                                .padding(bottom = 24.dp)
                                .align(Alignment.Start)
                        ) { it() }
                    }
                }
            }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
