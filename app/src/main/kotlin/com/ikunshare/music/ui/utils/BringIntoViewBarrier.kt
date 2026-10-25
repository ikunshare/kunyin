package com.ikunshare.music.ui.utils

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.relocation.BringIntoViewModifierNode

/**
 * Stops `bringIntoView()` requests from travelling any further up the tree.
 *
 * Scrollables below this node still honour the request; ancestors never see it. The tab pager
 * is a navigation surface, not a scroll container: a focused field or a selected chip inside an
 * off-screen page must never be able to swipe the pager to that page.
 */
fun Modifier.bringIntoViewBarrier(): Modifier = this then BringIntoViewBarrierElement

private object BringIntoViewBarrierElement : ModifierNodeElement<BringIntoViewBarrierNode>() {
    override fun create() = BringIntoViewBarrierNode()
    override fun update(node: BringIntoViewBarrierNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "bringIntoViewBarrier"
    }
    override fun hashCode(): Int = System.identityHashCode(this)
    override fun equals(other: Any?): Boolean = other === this
}

private class BringIntoViewBarrierNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) = Unit
}
