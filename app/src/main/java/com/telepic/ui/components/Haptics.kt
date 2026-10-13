package com.telepic.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/**
 * The app's haptic vocabulary, in one place.
 *
 * Telepic had no haptics at all: a long press that started a selection, a confirm that permanently
 * deleted forty files, and a refusal that left them alone all felt identical to the hand. That is a
 * feedback gap rather than a decorative one — on a gesture whose only other signal is a small check
 * badge, the tick is how the user knows the press registered before they lift.
 *
 * Three meanings, and deliberately only three:
 * - [selectionStart] — the press committed to something (entering selection, opening a tile's menu).
 * - [confirmDestructive] — an irreversible action went through.
 * - [reject] — an action was refused or failed.
 *
 * Every one of these goes through `View.performHapticFeedback`, **not** the `Vibrator` API. That
 * matters for two reasons: it is the platform's own feedback channel, so it obeys the user's haptics
 * setting and system strength without the app second-guessing it; and it needs **no `VIBRATE`
 * permission**, so the app's declared permission set — which is a documented invariant — stays
 * exactly as it is.
 *
 * `InlinedApi` is suppressed rather than obeyed: `HapticFeedbackConstants.CONFIRM` and `REJECT` are
 * compile-time `Int`s inlined into the call, so referencing them is harmless on older devices, and
 * [platformFeedback] guards the call with a version check — which is exactly the pattern that check
 * cannot see through. The suppression sits on the class because the constants are named at the call
 * sites below, not inside the guard.
 */
@Suppress("InlinedApi")
@Immutable
class TelepicHaptics(
    private val view: View,
    private val feedback: HapticFeedback,
) {
    /** The press committed to something: entering selection, opening a context menu. */
    fun selectionStart() {
        feedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * An irreversible action landed. `HapticFeedbackConstants.CONFIRM` is the platform's own word for
     * this, so it is preferred wherever it exists; below API 30 the generic long-press tick stands in
     * rather than nothing.
     */
    fun confirmDestructive() {
        platformFeedback(HapticFeedbackConstants.CONFIRM)
    }

    /** An action was refused or failed — the platform's "no", so the hand learns it too. */
    fun reject() {
        platformFeedback(HapticFeedbackConstants.REJECT)
    }

    private fun platformFeedback(constant: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(constant)
        } else {
            feedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
}

/**
 * The screen's haptic vocabulary, remembered so a tile does not rebuild it on every recomposition.
 *
 * A composition with no real `View` (a preview, or a test host that provides none) is not a reason to
 * crash: `LocalView` always resolves inside a real Android composition, and where it does not, the
 * call sites are unchanged — they simply have nothing to buzz.
 */
@Composable
fun rememberTelepicHaptics(): TelepicHaptics {
    val view = LocalView.current
    val feedback = LocalHapticFeedback.current
    return remember(view, feedback) { TelepicHaptics(view, feedback) }
}
