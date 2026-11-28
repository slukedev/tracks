package com.luke.tracks.ui.tv.settings

import androidx.compose.runtime.Immutable

/**
 * Moving one row of a short list with a remote: select picks a row up, Up and Down carry it,
 * select again puts it down there, and Back puts it back where it was.
 */
@Immutable
data class RemoteReorder<T>(
    val items: List<T>,
    val lifted: T? = null,
    /** The order before the row was picked up, for Back. */
    private val before: List<T> = items,
) {
    val isLifted: Boolean get() = lifted != null

    fun pickUp(item: T): RemoteReorder<T> = if (item in items) RemoteReorder(items, item, items) else this

    /** Whether the lifted row can go [by] rows (negative is up) without leaving the list. */
    fun canMove(by: Int): Boolean {
        val from = items.indexOf(lifted ?: return false)
        return by != 0 && from + by in items.indices
    }

    fun move(by: Int): RemoteReorder<T> {
        if (!canMove(by)) return this
        val from = items.indexOf(lifted)
        val next = items.toMutableList().apply { add(from + by, removeAt(from)) }
        return copy(items = next)
    }

    /** Puts the row down where it is, with the order to save when it moved. */
    fun drop(): Pair<RemoteReorder<T>, List<T>?> = RemoteReorder(items) to items.takeIf { isLifted && it != before }

    /** Back: the row goes back where it was. */
    fun cancel(): RemoteReorder<T> = RemoteReorder(before)

    /**
     * The list as it now stands elsewhere. A row being carried keeps its place while the same
     * rows are there; when a row comes or goes, the move is called off.
     */
    fun following(latest: List<T>): RemoteReorder<T> = when {
        !isLifted -> if (latest == items) this else RemoteReorder(latest)
        latest.toSet() == items.toSet() && latest.size == items.size -> this
        else -> RemoteReorder(latest)
    }
}
