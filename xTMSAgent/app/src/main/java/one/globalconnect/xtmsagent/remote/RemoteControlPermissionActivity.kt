package one.globalconnect.xtmsagent.remote

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log

private const val TAG = "RCPermissionActivity"
private const val REQ_PROJECTION = 1001

class RemoteControlPermissionActivity : Activity() {

    companion object {
        const val EXTRA_CONFIG_JSON = "remoteControlConfigJson"
        const val EXTRA_TERMINAL_ID = "terminalId"
    }

    private lateinit var mpm: MediaProjectionManager
    private var waitingForAccessibility = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection service is unavailable", e)
            finish()
            return
        }

        if (!RemoteControlAccessibilityService.isEnabled(this)) {
            waitingForAccessibility = true
            AlertDialog.Builder(this)
                .setTitle("Remote Control Setup Required")
                .setMessage(
                    "The Remote Control accessibility service must be enabled for unattended screen sharing.\n\n" +
                        "Tap Open Settings, find xTMSAgent > Remote Control, and turn the switch on.",
                )
                .setPositiveButton("Open Settings") { _, _ -> openAccessibilitySettings() }
                .setNegativeButton("Cancel") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }

        requestProjection()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForAccessibility && RemoteControlAccessibilityService.isEnabled(this)) {
            waitingForAccessibility = false
            requestProjection()
        }
    }

    private fun openAccessibilitySettings() {
        val component = ComponentName(this, RemoteControlAccessibilityService::class.java)
            .flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            val args = Bundle().apply {
                putString(":settings:fragment_args_key", component)
            }
            putExtra(":settings:show_fragment_args", args)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun requestProjection() {
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("Using deprecated onActivityResult for minSdk 29 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                val svcIntent = Intent(this, RemoteControlService::class.java).apply {
                    action = RemoteControlService.ACTION_START
                    putExtra(
                        RemoteControlService.EXTRA_CONFIG_JSON,
                        intent.getStringExtra(EXTRA_CONFIG_JSON),
                    )
                    putExtra(
                        RemoteControlService.EXTRA_TERMINAL_ID,
                        intent.getStringExtra(EXTRA_TERMINAL_ID),
                    )
                    putExtra(RemoteControlService.EXTRA_PROJECTION_RESULT, resultCode)
                    putExtra(RemoteControlService.EXTRA_PROJECTION_DATA, data)
                }
                try {
                    startForegroundService(svcIntent)
                    Log.i(TAG, "MediaProjection granted; RemoteControlService started")
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to start RemoteControlService", e)
                }
            } else {
                Log.w(TAG, "MediaProjection permission denied or cancelled")
            }
        }
        finish()
    }
}
