package com.telepic.ui.screens.viewer

import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Turns the video surface's left-half drag into screen brightness by overriding the hosting window's
 * `screenBrightness` — a per-window override that needs no permission and never touches the system
 * setting. The value the window had on entry is restored on dispose, so leaving the Viewer hands the
 * screen back to whatever the device or the user had set.
 */
internal class ScreenBrightnessController(private val window: Window) {
    private val original: Float = window.attributes.screenBrightness

    /**
     * Where a drag should start. A window with no override reports [WindowManager.LayoutParams
     * .BRIGHTNESS_OVERRIDE_NONE] (-1); there is no readable system brightness without a permission,
     * so the gesture starts from the middle rather than guessing at the true value.
     */
    val initialFraction: Float =
        if (original in MIN_BRIGHTNESS..1f) original else FALLBACK_FRACTION

    /** Applies [fraction] (clamped to a floor that is never fully black) and returns what was set. */
    fun set(fraction: Float): Float {
        val value = fraction.coerceIn(MIN_BRIGHTNESS, 1f)
        runCatching {
            val attributes = window.attributes
            attributes.screenBrightness = value
            window.attributes = attributes
        }
        return value
    }

    /** Puts the window back on the value it had before the first drag. */
    fun restore() {
        runCatching {
            val attributes = window.attributes
            attributes.screenBrightness = original
            window.attributes = attributes
        }
    }

    private companion object {
        /** Never fully black: the control that turned the screen off would be invisible to undo it. */
        const val MIN_BRIGHTNESS = 0.01f
        const val FALLBACK_FRACTION = 0.5f
    }
}

/**
 * The brightness controller for the current composition's window, or null outside an Activity (a
 * preview, a test) — where the gesture no-ops instead of crashing. Disposing restores the original
 * brightness, so navigation away cannot leave the screen dimmed.
 */
@Composable
internal fun rememberScreenBrightnessController(): ScreenBrightnessController? {
    val view = LocalView.current
    val controller = remember(view) {
        view.context.hostingWindow()?.let(::ScreenBrightnessController)
    }
    DisposableEffect(controller) {
        onDispose { controller?.restore() }
    }
    return controller
}
