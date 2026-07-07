package one.globalconnect.xtmsagent.remote

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "RemoteControlMgr"

/**
 * Orchestrates a Kinesis Video Streams WebRTC remote-control session.
 */
class RemoteControlManager(
    private val context: Context,
    private val config: RemoteControlConfig,
    private val projectionData: Intent,
    private val onSessionEnded: () -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var timeoutJob: Job? = null
    private var viewerConnected = false
    private var client: KinesisWebRtcRemoteClient? = null

    fun start() {
        Log.i(TAG, "Starting Kinesis remote control session for ${config.terminalId}")
        client = KinesisWebRtcRemoteClient(
            context = context,
            config = config,
            projectionData = projectionData,
            onViewerConnected = {
                if (!viewerConnected) {
                    viewerConnected = true
                    timeoutJob?.cancel()
                    Log.i(TAG, "Viewer connected; timeout cancelled")
                }
            },
            onSessionEnded = { scope.launch { onSessionEnded() } },
        )
        client?.start()
        startViewerTimeout()
    }

    fun stop() {
        Log.i(TAG, "Stopping remote control session")
        endSession()
    }

    private fun startViewerTimeout() {
        timeoutJob = scope.launch {
            delay(config.viewerTimeoutSeconds * 1000L)
            if (!viewerConnected) {
                Log.w(TAG, "Viewer did not connect within ${config.viewerTimeoutSeconds}s; ending session")
                endSession()
            }
        }
    }

    private fun endSession() {
        timeoutJob?.cancel()
        client?.stop()
        client = null
    }
}
