package one.globalconnect.xtmsagent.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

private const val TAG = "RCAccessibility"

// Triggers for the auto-click. We listen for both:
//  - The SystemUI dialog itself (when TYPE_WINDOW_STATE_CHANGED fires for it)
//  - Our own transparent RemoteControlPermissionActivity, which always appears
//    just before the dialog — reliable fallback when the SystemUI event doesn't fire.
private const val PROJECTION_DIALOG_SUFFIX   = "MediaProjectionPermissionActivity"
private const val OUR_PERMISSION_ACT_SUFFIX  = "RemoteControlPermissionActivity"

// Wait past Android 10+'s ~1 s anti-auto-click window on the consent dialog.
// When triggered by our own activity we add extra time for the dialog to appear.
private const val DELAY_VIA_DIALOG_MS = 1500L
private const val DELAY_VIA_OWN_MS   = 2000L

/**
 * Accessibility service that:
 *  1. Injects touch gestures and global key actions for the remote control session.
 *  2. Auto-clicks the "Start now" button on the MediaProjection consent dialog so
 *     unattended terminals never require human interaction.
 *
 * Must be enabled in Settings → Accessibility → xTMSAgent → Remote Control.
 * Standard Device Owner APIs can allow-list this service but cannot enable it;
 * supported NEXGO terminals use [RemoteControlAccessibilityProvisioner] during setup.
 */
class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: RemoteControlAccessibilityService? = null
        fun isAvailable(): Boolean = instance != null

        fun isEnabled(context: Context): Boolean {
            val target = ComponentName(context, RemoteControlAccessibilityService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(":").any { it.equals(target, ignoreCase = true) }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clickToken  = Object()

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "RemoteControlAccessibilityService connected")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (one.globalconnect.xtmsagent.recovery.StartupRecoveryGuard.inRecovery) return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val className = event.className?.toString() ?: return
        Log.d(TAG, "Window changed: pkg=${event.packageName} class=$className")

        val delayMs = when {
            className.endsWith(PROJECTION_DIALOG_SUFFIX)  -> DELAY_VIA_DIALOG_MS
            className.endsWith(OUR_PERMISSION_ACT_SUFFIX) -> DELAY_VIA_OWN_MS
            else -> return
        }

        Log.i(TAG, "Scheduling consent-dialog click in ${delayMs}ms (trigger=$className)")
        // Cancel any previous pending click before scheduling a new one
        mainHandler.removeCallbacksAndMessages(clickToken)
        mainHandler.postDelayed(::clickConsentDialog, clickToken, delayMs)
    }

    private fun clickConsentDialog() {
        // Collect roots from active window + all windows (requires canRetrieveWindowContent
        // and flagRetrieveInteractiveWindows in the service XML)
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots += it }
        try { windows?.mapNotNull { it.root }?.let { roots += it } } catch (_: Exception) {}

        Log.d(TAG, "Searching ${roots.size} root(s) for button1")
        roots.forEachIndexed { i, root ->
            Log.d(TAG, "=== Root $i pkg=${root.packageName} ===")
            dumpNodeTree(root, 0)
        }

        for (root in roots) {
            val button = root.findAccessibilityNodeInfosByViewId("android:id/button1")
                ?.firstOrNull { it.isEnabled }
            if (button != null) {
                val bounds = Rect().also { button.getBoundsInScreen(it) }
                Log.i(TAG, "Clicking button1 text='${button.text}' bounds=$bounds")
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "Auto-click dispatched on MediaProjection consent dialog")
                return
            }
        }
        Log.w(TAG, "button1 not found in any window — see node dumps above")
    }

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    private fun dumpNodeTree(node: AccessibilityNodeInfo?, depth: Int) {
        node ?: return
        val indent  = "  ".repeat(depth)
        val bounds  = Rect().also { node.getBoundsInScreen(it) }
        Log.d(TAG, "$indent[${node.className}]" +
            " id='${node.viewIdResourceName}'" +
            " text='${node.text}'" +
            " desc='${node.contentDescription}'" +
            " enabled=${node.isEnabled}" +
            " clickable=${node.isClickable}" +
            " bounds=$bounds")
        for (i in 0 until node.childCount) dumpNodeTree(node.getChild(i), depth + 1)
    }

    // ── Remote input injection ─────────────────────────────────────────────────

    fun injectTouch(x: Float, y: Float, action: String) {
        Log.i(TAG, "Dispatching touch action=$action at ($x,$y)")
        val path       = Path().apply { moveTo(x, y) }
        val durationMs = if (action == "move") 16L else 1L
        val continued  = action == "down" || action == "move"
        val stroke  = GestureDescription.StrokeDescription(path, 0L, durationMs, continued)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Touch action=$action completed")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Touch action=$action cancelled")
            }
        }, null)
    }

    fun injectDrag(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        Log.i(TAG, "Dispatching drag from ($startX,$startY) to ($endX,$endY), duration=${durationMs}ms")
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs, false)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "Drag completed")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Drag cancelled")
            }
        }, null)
    }

    fun injectKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK       -> performGlobalAction(GLOBAL_ACTION_BACK)
            KeyEvent.KEYCODE_HOME       -> performGlobalAction(GLOBAL_ACTION_HOME)
            KeyEvent.KEYCODE_APP_SWITCH -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> Log.d(TAG, "Unsupported keyCode $keyCode — ignored")
        }
    }

    fun injectGlobalAction(action: String) {
        val globalAction = when (action.lowercase()) {
            "notifications", "notification_shade", "status_bar" -> GLOBAL_ACTION_NOTIFICATIONS
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> {
                Log.w(TAG, "Unsupported global action '$action'")
                return
            }
        }

        if (!performGlobalAction(globalAction)) {
            Log.w(TAG, "Android rejected global action '$action'")
        }
    }
}
