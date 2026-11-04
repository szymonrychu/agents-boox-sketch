package io.github.szymonrychu.booxsketch.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode

/** Indication that draws nothing: e-ink panels ghost on ripples and animations. */
object NoIndication : IndicationNodeFactory {
    // A fresh node per use: one Modifier.Node instance can only be delegated to once.
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoIndicationNode()

    override fun equals(other: Any?) = other === this

    override fun hashCode() = -1

    private class NoIndicationNode :
        Modifier.Node(),
        DrawModifierNode {
        override fun ContentDrawScope.draw() = drawContent()
    }
}
