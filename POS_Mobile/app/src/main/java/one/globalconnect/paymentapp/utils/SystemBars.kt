package one.globalconnect.paymentapp.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Finds the [Activity] instance associated with this [Context], if any.
 */
private tailrec fun Context?.findActivity(): Activity? = when (this) {
    null -> null
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Returns a [WindowInsetsControllerCompat] that can be used to control the system bars
 * for the current composition, or `null` if no [Activity] is available (for example in Preview).
 */
@Composable
fun rememberWindowInsetsController(): WindowInsetsControllerCompat? {
    val view = LocalView.current
    if (view.isInEditMode) {
        return null
    }

    val activity = view.context.findActivity()
    return remember(view, activity) {
        activity?.window?.let { WindowCompat.getInsetsController(it, view) }
    }
}

/**
 * Hides both the status and navigation bars while enabling the swipe gesture to temporarily
 * reveal them.
 */
fun WindowInsetsControllerCompat.hideSystemBarsForImmersive() {
    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    hide(WindowInsetsCompat.Type.systemBars())
}
