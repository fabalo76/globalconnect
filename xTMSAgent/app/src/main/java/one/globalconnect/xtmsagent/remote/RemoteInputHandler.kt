package one.globalconnect.xtmsagent.remote

import android.util.Log
import org.json.JSONObject

private const val TAG = "RemoteInputHandler"

/**
 * Parses JSON input events received from the WebRTC data channel and dispatches them
 * via [RemoteControlAccessibilityService].
 *
 * Touch events use normalized coordinates (0..1) mapped to [screenWidth] x [screenHeight].
 *
 * Protocol:
 *   {"type":"touch","action":"down|move|up","x":0.5,"y":0.3,"pointerId":0}
 *   {"type":"touch","action":"drag","startX":0.5,"startY":0.7,"endX":0.5,"endY":0.2,"durationMs":650}
 *   {"type":"key","keyCode":4}
 *   {"type":"quality","quality":"low|medium|high"}
 */
class RemoteInputHandler(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onQualityChange: (String) -> Unit = {},
) {

    fun handle(json: String) {
        try {
            val obj = JSONObject(json)
            when (val type = obj.optString("type")) {
                "touch" -> handleTouch(obj)
                "key" -> handleKey(obj)
                "quality" -> handleQuality(obj)
                else -> Log.w(TAG, "Unsupported remote input type='$type' payload=$json")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse input event: $json - ${e.message}")
        }
    }

    private fun handleTouch(obj: JSONObject) {
        val svc = RemoteControlAccessibilityService.instance
        if (svc == null) {
            Log.w(TAG, "AccessibilityService not available - touch event dropped")
            return
        }
        val action = obj.optString("action", "down")
        if (action == "drag") {
            val startX = (obj.optDouble("startX", obj.optDouble("x", 0.5)) * screenWidth)
                .toFloat()
                .coerceIn(0f, screenWidth.toFloat())
            val startY = (obj.optDouble("startY", obj.optDouble("y", 0.5)) * screenHeight)
                .toFloat()
                .coerceIn(0f, screenHeight.toFloat())
            val endX = (obj.optDouble("endX", obj.optDouble("x", 0.5)) * screenWidth)
                .toFloat()
                .coerceIn(0f, screenWidth.toFloat())
            val endY = (obj.optDouble("endY", obj.optDouble("y", 0.5)) * screenHeight)
                .toFloat()
                .coerceIn(0f, screenHeight.toFloat())
            val durationMs = obj.optLong("durationMs", 650L).coerceIn(100L, 2_000L)
            Log.i(TAG, "Remote touch drag received start=($startX,$startY) end=($endX,$endY) duration=${durationMs}ms")
            svc.injectDrag(startX, startY, endX, endY, durationMs)
            return
        }

        val x = (obj.optDouble("x", 0.5) * screenWidth).toFloat().coerceIn(0f, screenWidth.toFloat())
        val y = (obj.optDouble("y", 0.5) * screenHeight).toFloat().coerceIn(0f, screenHeight.toFloat())
        if (action == "down" || action == "move" || action == "up" || action == "cancel") {
            Log.i(TAG, "Remote touch $action received at ($x,$y)")
            svc.injectTouch(x, y, action)
        } else {
            Log.w(TAG, "Unsupported remote touch action='$action' payload=$obj")
        }
    }

    private fun handleKey(obj: JSONObject) {
        val svc = RemoteControlAccessibilityService.instance
        if (svc == null) {
            Log.w(TAG, "AccessibilityService not available - key event dropped")
            return
        }
        val keyCode = obj.optInt("keyCode", -1)
        if (keyCode >= 0) svc.injectKey(keyCode)
    }

    private fun handleQuality(obj: JSONObject) {
        val quality = obj.optString("quality", obj.optString("preset", "")).lowercase()
        if (quality == "low" || quality == "medium" || quality == "high") {
            Log.i(TAG, "Remote quality request received: $quality")
            onQualityChange(quality)
        } else {
            Log.w(TAG, "Unsupported remote quality preset: $quality")
        }
    }
}
