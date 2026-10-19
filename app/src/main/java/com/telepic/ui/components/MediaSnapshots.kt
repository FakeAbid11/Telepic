package com.telepic.ui.components

import androidx.compose.runtime.Immutable
import com.telepic.domain.backup.MediaBackupVisualState

/**
 * An immutable snapshot of the per-item backup state, keyed by the item's MediaStore id.
 *
 * `Map` is an *interface*, so the Compose compiler infers it as unstable: a composable taking one is
 * never skippable and re-runs on every recomposition of its parent. The media grid is recomposed by
 * every backup-progress tick, so the whole grid body — and the date rail inside it — re-ran several
 * times a second during an upload.
 *
 * Wrapping it in a value type with content equality puts the comparison on the *content* rather
 * than on the map instance, so a tick that changed nothing skips the grid.
 */
@Immutable
data class MediaBackupStateSnapshot(
    val states: Map<Long, MediaBackupVisualState>,
) {
    /** The state of one item; [MediaBackupVisualState.NONE] for an item the queue has not seen. */
    operator fun get(id: Long): MediaBackupVisualState = states[id] ?: MediaBackupVisualState.NONE

    val isEmpty: Boolean get() = states.isEmpty()
}

/**
 * An immutable snapshot of a set of MediaStore ids.
 *
 * The same reason as [MediaBackupStateSnapshot]: `Set` is an interface, so a composable taking one
 * is never skippable.
 */
@Immutable
data class MediaIdSet(
    val ids: Set<Long>,
) {
    operator fun contains(id: Long): Boolean = id in ids

    val size: Int get() = ids.size

    companion object {
        val Empty = MediaIdSet(emptySet())
    }
}
