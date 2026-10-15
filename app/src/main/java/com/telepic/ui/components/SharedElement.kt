@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.telepic.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.telepic.ui.theme.LocalReduceMotion

/**
 * The two scopes `Modifier.sharedElement` needs, carried by composition local rather than threaded
 * through every parameter list.
 *
 * A shared element is inherently a *pair*: the tile in the grid and the image in the Viewer must
 * agree on a key, and both must be inside the same [SharedTransitionLayout] and inside their own
 * destination's `AnimatedContentScope`. Those two scopes live at the navigation layer, but the
 * elements that consume them are four levels down (PhotosScreen → MediaRegion → MediaGrid →
 * MediaTile) and are also reached from the album screens. Passing them as parameters would put two
 * unused-scope parameters on every composable along that path, for a feature none of them implement.
 *
 * Both default to `null`, and every consumer degrades to a plain [Modifier] when they are absent —
 * so a tile rendered outside the navigation graph (a preview, a unit test) is an ordinary tile
 * rather than a crash or a silently missing image.
 */
internal val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
internal val LocalSharedElementVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * The shared-element key for one local media item.
 *
 * Namespaced and derived from the stable MediaStore id — never from a grid position or a filename —
 * so a re-sorted or re-paged grid still pairs the tile the user pressed with the image that opens.
 * The same id is what the Viewer's route argument carries, which is what lets the two ends agree
 * without either of them knowing about the other.
 */
internal fun mediaSharedElementKey(mediaId: Long): String = "telepic-media-$mediaId"

/** Publishes the navigation destination's shared-element scopes to everything composed inside it. */
@Composable
internal fun ProvideSharedElementScopes(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSharedTransitionScope provides sharedTransitionScope,
        LocalSharedElementVisibilityScope provides animatedVisibilityScope,
        content = content,
    )
}

/**
 * The shared-element modifier for [mediaId]'s image, or [Modifier] when this composition is not
 * inside a shared-element destination or there is no local item to pair with.
 *
 * A null [mediaId] is the honest case for a cloud item: it has no tile in a local grid, so there is
 * nothing to grow from and the Viewer should simply appear. Returning [Modifier] rather than
 * registering an unmatched element keeps that a plain transition instead of a shared one with a
 * missing partner.
 *
 * `ModifierFactoryExtensionFunction` is suppressed rather than obeyed: lint's rule for a function that
 * returns a [Modifier] is to make it `Modifier.xxx()`, but this one has to be `@Composable` — it reads
 * two composition locals and calls `rememberSharedContentState` — and Compose's own rule for a
 * *composable* modifier factory is the opposite one (a composable factory cannot be hoisted, skipped
 * or safely used as a plain modifier). Obeying lint here would trade this warning for
 * `ComposableModifierFactory`. The `remember`-prefixed name says what it actually is: a composable
 * state helper that happens to hand back a modifier.
 */
@Suppress("ModifierFactoryExtensionFunction")
@Composable
internal fun rememberSharedMediaModifier(mediaId: Long?): Modifier {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalSharedElementVisibilityScope.current
    // Users who prefer reduced motion often find the long spring-based shared-element zoom
    // disorienting; fall back to a plain cross-fade by not registering the shared element.
    if (LocalReduceMotion.current) return Modifier
    return if (mediaId != null && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                state = rememberSharedContentState(key = mediaSharedElementKey(mediaId)),
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    } else {
        Modifier
    }
}
