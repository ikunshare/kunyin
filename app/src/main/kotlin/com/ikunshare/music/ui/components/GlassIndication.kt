package com.ikunshare.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.launch

private const val GLOW_ALPHA = 0.2f
private const val GLOW_RADIUS_FRACTION = 0.9f

/**
 * Liquid Glass touch feedback: light blooms from the finger instead of an ink ripple.
 * The glow is radial and fades out before it reaches rounded corners, so it needs no clip.
 */
internal class GlassIndication(private val glow: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        GlassIndicationNode(interactionSource, glow)

    override fun equals(other: Any?): Boolean = other is GlassIndication && other.glow == glow

    override fun hashCode(): Int = glow.hashCode()
}

private class GlassIndicationNode(
    private val interactionSource: InteractionSource,
    private val glow: Color
) : Modifier.Node(), DrawModifierNode {
    private val progress = Animatable(0f, 0.001f)
    private var pressPosition = Offset.Unspecified

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        pressPosition = interaction.pressPosition
                        coroutineScope.launch {
                            progress.animateTo(1f, spring(Spring.DampingRatioNoBouncy, 900f, 0.001f))
                        }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> coroutineScope.launch {
                        // A quick tap still shows the light before it fades.
                        if (progress.value < 0.6f) {
                            progress.animateTo(0.6f, spring(Spring.DampingRatioNoBouncy, 1400f, 0.001f))
                        }
                        progress.animateTo(0f, spring(Spring.DampingRatioNoBouncy, 160f, 0.001f))
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val p = progress.value
        if (p <= 0.001f) return
        val center = if (pressPosition.isSpecified) pressPosition else center
        drawRect(
            Brush.radialGradient(
                listOf(glow.copy(alpha = glow.alpha * GLOW_ALPHA * p), Color.Transparent),
                center = center,
                radius = size.minDimension * GLOW_RADIUS_FRACTION * (0.7f + 0.3f * p)
            )
        )
    }
}
