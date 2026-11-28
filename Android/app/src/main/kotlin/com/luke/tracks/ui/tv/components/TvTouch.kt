package com.luke.tracks.ui.tv.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/**
 * Taps and long presses for a tv-material clickable, which only answers the remote's keys, so a
 * phone in TV Mode works by touch too. A tap focuses the control, so the remote carries on from
 * it, then clicks it; a press dips it as the centre key does. A drag that becomes a scroll is
 * left to the list. Goes before the clickable in the chain, with its [interactionSource].
 */
fun Modifier.tvTouch(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    focusOnTap: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = this then TvTouchElement(interactionSource, enabled, focusOnTap, onLongClick, onClick)

private data class TvTouchElement(
    val source: MutableInteractionSource,
    val enabled: Boolean,
    val focusOnTap: Boolean,
    val onLongClick: (() -> Unit)?,
    val onClick: () -> Unit,
) : ModifierNodeElement<TvTouchNode>() {
    override fun create() = TvTouchNode(source, enabled, focusOnTap, onLongClick, onClick)

    override fun update(node: TvTouchNode) = node.update(source, enabled, focusOnTap, onLongClick, onClick)

    override fun InspectorInfo.inspectableProperties() {
        name = "tvTouch"
        properties["enabled"] = enabled
    }
}

private class TvTouchNode(
    private var source: MutableInteractionSource,
    private var enabled: Boolean,
    private var focusOnTap: Boolean,
    private var onLongClick: (() -> Unit)?,
    private var onClick: () -> Unit,
) : DelegatingNode(), FocusRequesterModifierNode {
    private val input = delegate(
        SuspendingPointerInputModifierNode {
            // Without a long-press action, holding and letting go is still a tap.
            val longPress = onLongClick != null
            detectTapGestures(
                onPress = { offset ->
                    if (!enabled) return@detectTapGestures
                    val press = PressInteraction.Press(offset)
                    source.emit(press)
                    val released = tryAwaitRelease()
                    source.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                },
                onLongPress = if (longPress) { _ -> act { onLongClick?.invoke() } } else null,
                onTap = { act(onClick) },
            )
        },
    )

    private fun act(action: () -> Unit) {
        if (!enabled) return
        if (focusOnTap) requestFocus()
        action()
    }

    fun update(
        source: MutableInteractionSource,
        enabled: Boolean,
        focusOnTap: Boolean,
        onLongClick: (() -> Unit)?,
        onClick: () -> Unit,
    ) {
        val restart = source != this.source || enabled != this.enabled || (onLongClick != null) != (this.onLongClick != null)
        this.source = source
        this.enabled = enabled
        this.focusOnTap = focusOnTap
        this.onLongClick = onLongClick
        this.onClick = onClick
        if (restart) input.resetPointerInputHandler()
    }
}
