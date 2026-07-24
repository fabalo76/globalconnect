package one.globalconnect.xtmsagent.mqtt

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import one.globalconnect.xtmsagent.BlockedActivity
import one.globalconnect.xtmsagent.MainActivity
import one.globalconnect.xtmsagent.R
import one.globalconnect.xtmsagent.remote.RemoteControlConfig
import one.globalconnect.xtmsagent.remote.RemoteControlPermissionActivity
import one.globalconnect.xtmsagent.remote.RemoteControlService
import one.globalconnect.xtmsagent.TMSFunc
import one.globalconnect.xtmsagent.easy.EasyAckReport
import one.globalconnect.xtmsagent.launcher.LauncherConfigManager
import one.globalconnect.xtmsagent.params.ParamManager
import one.globalconnect.xtmsagent.BuildConfig
import one.globalconnect.xtmsagent.OperatorMessageActivity
import one.globalconnect.xtmsagent.mqtt.downloads.AwsDeviceDownloadManager
import one.globalconnect.xtmsagent.mqtt.notifications.ACTION_BLOCK_TERMINAL
import one.globalconnect.xtmsagent.mqtt.notifications.ACTION_TERMINAL_NOT_REGISTERED
import one.globalconnect.xtmsagent.mqtt.notifications.ACTION_UNBLOCK_TERMINAL
import one.globalconnect.xtmsagent.mqtt.notifications.EXTRA_MESSAGE_TEXT
import one.globalconnect.xtmsagent.mqtt.notifications.TmsNotificationHandler
import one.globalconnect.xtmsagent.mqtt.persistence.TmsCredentialStore
import one.globalconnect.xtmsagent.mqtt.status.TmsStatusWorker
import one.globalconnect.xtmsagent.mqtt.versions.AppUpdateManager
import one.globalconnect.xtmsagent.mqtt.versions.TermVersionChecker
import one.globalconnect.xtmsagent.mqtt.tls.AwsIotCertificateStore
import one.globalconnect.xtmsagent.licensing.ApplicationLicenseBroker
import one.globalconnect.xtmsagent.licensing.KioskModeController
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
import one.globalconnect.xtmsagent.settlement.ForceSettlementManager
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import kotlin.math.min
import java.time.Instant
import kotlinx.coroutines.flow.first

private const val TAG = "TmsMqttManager"

/**
 * Broadcast sent after a server-triggered housekeeping cycle completes so that
 * MainActivity (if in the foreground) can refresh logos and the app button grid.
 */
const val ACTION_HOUSEKEEPING_COMPLETE = "one.globalconnect.xtmsagent.ACTION_HOUSEKEEPING_COMPLETE"

// Reconnection backoff steps in milliseconds.
// Pattern: immediate → 5 s → 15 s → 30 s → 60 s → 120 s → 300 s (cap)
private val BACKOFF_STEPS_MS = longArrayOf(0L, 5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L)
private const val BACKOFF_JITTER_FRACTION = 0.20  // ±20 % random jitter

/**
 * Singleton that manages the TMS MQTT connection for the terminal.
 *
 * Responsibilities:
 *   - Build and connect the MQTT client with AWS IoT client-certificate authentication
 *   - Subscribe to terminal-specific notify and broadcast topics (QoS 1)
 *   - Reconnect with exponential backoff on disconnection
 *   - React to network changes (WiFi ↔ 4G) via ConnectivityManager callback
 *   - Re-provision the AWS IoT certificate after authorization failures
 *
 * Call [initialize] once from TmsMqttService.onCreate(), then [connect].
 */
object TmsMqttManager {

    private lateinit var appContext: Context
    private lateinit var termId: String
    private lateinit var brokerHost: String
    private lateinit var credentialStore: TmsCredentialStore
    private lateinit var awsIotCertificateStore: AwsIotCertificateStore
    private lateinit var notificationHandler: TmsNotificationHandler

    @Volatile private var mqttClient: Mqtt3AsyncClient? = null
    @Volatile private var isConnected: Boolean = false
    @Volatile private var isShuttingDown: Boolean = false
    @Volatile private var networkAvailable: Boolean = false
    private val networkCallbackRegistered = AtomicBoolean(false)
    @Volatile private var consecutiveAuthFailures: Int = 0
    // Set when publishFullStatusReport() fails because the client is not connected.
    // Cleared once the report is successfully delivered after reconnect.
    @Volatile private var pendingStatusPublish: Boolean = false

    private val managerScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("TmsMqttManager") +
            one.globalconnect.xtmsagent.loggingCoroutineExceptionHandler(TAG)
    )

    private var reconnectJob: Job? = null

    // Guards against concurrent connection attempts from multiple code paths
    // (disconnect listener, network callback, external connect() calls).
    // isConnecting is set true by scheduleReconnect and cleared in the finally
    // block of the launched coroutine, guarded by connectGeneration so a stale
    // finally from a cancelled job does not clear the flag for a newer job.
    private val isConnecting = AtomicBoolean(false)
    private val connectionAttemptActive = AtomicBoolean(false)
    @Volatile private var connectGeneration = 0
    private val clientSequence = AtomicLong(0)
    @Volatile private var activeClientSequence = 0L

    /**
     * Must be called once before [connect]. Idempotent — safe to call on service restarts.
     *
     * @param context    Application context.
     * @param termId     Terminal unique identifier (device SN).
     * @param brokerHost FQDN of the TMS MQTT broker (from Launcher_Config.JSON server_addr).
     */
    fun initialize(context: Context, termId: String, brokerHost: String) {
        this.appContext             = context.applicationContext
        this.termId                 = termId
        this.brokerHost             = brokerHost
        this.credentialStore        = TmsCredentialStore(appContext)
        this.awsIotCertificateStore = AwsIotCertificateStore(appContext)
        this.notificationHandler    = TmsNotificationHandler(appContext)
        isShuttingDown = false
        consecutiveAuthFailures = 0
        isConnecting.set(false)
        activeClientSequence = 0L
        networkAvailable = hasActiveNetwork()
        if (networkCallbackRegistered.compareAndSet(false, true)) {
            registerNetworkCallback()
        }
        Log.i(TAG, "Initialized for terminal $termId @ $brokerHost networkAvailable=$networkAvailable")
    }

    private fun handleTaskMessage(payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            val json = org.json.JSONObject(String(payload, Charsets.UTF_8))
            val taskId = json.optString("taskId", json.optString("TaskId"))
            val taskType = json.optString("taskType", json.optString("TaskType"))
            if (taskId.isBlank() || taskType.isBlank()) {
                Log.w(TAG, "Task message missing taskId/taskType: $json")
                return
            }

            handleUicTask(taskId, taskType, json.optJSONObject("payload") ?: json.optJSONObject("Payload"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse task message: ${e.message}", e)
        }
    }

    private fun handleUicTask(taskId: String, taskType: String, payload: org.json.JSONObject?) {
        Log.i(TAG, "Global Connect task received: id=$taskId type=$taskType")
        try {
            when (taskType.lowercase()) {
                "refreshconfig", "parametersdownload" -> {
                    val sent = publishConfigRequest(payload)
                    publishTaskAck(taskId, sent, if (sent) null else "MQTT client is not connected")
                }
                "applicationdownload", "firmwaredownload", "updatefirmware", "bootanimationdownload" -> {
                    managerScope.launch(Dispatchers.IO) {
                        val result = AwsDeviceDownloadManager.executeTask(appContext, taskId, taskType, payload)
                        publishTaskAck(taskId, result.success, result.errorMessage, result.status, result.statusMessage)
                        if (result.success) publishFullStatusReport()
                    }
                }
                "canceltask" -> {
                    val originalTaskId = readPayloadString(payload, "originalTaskId", "OriginalTaskId") ?: taskId
                    val cancelled = AwsDeviceDownloadManager.cancelTask(appContext, originalTaskId)
                    publishTaskAck(
                        originalTaskId,
                        cancelled,
                        if (cancelled) null else "Task identifier is missing",
                        if (cancelled) "cancelled" else "failed",
                        if (cancelled) "Staged task and schedule removed" else null
                    )
                }
                "exitkiosk" -> {
                    KioskModeController.setLocked(appContext, false) { success, error ->
                        publishTaskAck(taskId, success, error, if (success) "executed" else "failed")
                    }
                }
                "deleteapplication", "applicationdelete" -> {
                    managerScope.launch(Dispatchers.IO) {
                        handleDeleteApplicationTask(taskId, payload)
                    }
                }
                "refreshstatus" -> {
                    publishFullStatusReport { success, errorMessage ->
                        publishTaskAck(taskId, success, errorMessage)
                    }
                }
                "launcherconfigdownload" -> {
                    managerScope.launch(Dispatchers.IO) {
                        val success = LauncherConfigManager.downloadAndApplyAws(appContext, payload)
                        publishTaskAck(taskId, success, if (success) null else "Launcher config download failed")
                        if (success) publishVersionRequest()
                    }
                }
                "displaymessage" -> {
                    showDisplayMessage(payload)
                    publishTaskAck(taskId, true)
                }
                "rebootdevice", "reboot" -> {
                    handleRebootDeviceTask(taskId)
                }
                "blockdevice", "blocklane" -> {
                    handleBlockTask(payload)
                    publishTaskAck(taskId, true)
                }
                "unblockdevice", "unblocklane" -> {
                    handleUnblockTask()
                    publishTaskAck(taskId, true)
                }
                "forcesettlement" -> {
                    val sent = ForceSettlementManager.request(appContext, taskId, payload)
                    publishTaskAck(taskId, sent, if (sent) null else "No payment application registered for settlement")
                }
                else -> {
                    Log.w(TAG, "Unsupported Global Connect task type: $taskType")
                    publishTaskAck(taskId, false, "Unsupported task type: $taskType")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Global Connect task failed: id=$taskId type=$taskType message=${e.message}", e)
            publishTaskAck(taskId, false, e.message ?: "Task failed")
        }
    }

    private fun handleRebootDeviceTask(taskId: String) {
        managerScope.launch(Dispatchers.IO) {
            val platform = try {
                com.nexgo.oaf.apiv3.APIProxy.getDeviceEngine(appContext).platform
            } catch (e: Exception) {
                Log.e(TAG, "RebootDevice failed: ${e.message}", e)
                publishTaskAck(taskId, false, e.message ?: "Device reboot failed")
                return@launch
            }

            Log.w(TAG, "RebootDevice requested by Global Connect")
            publishTaskAck(taskId, true)
            delay(1_000L)
            try {
                platform.rebootDevice()
            } catch (e: Exception) {
                Log.e(TAG, "rebootDevice() failed after task ACK: ${e.message}", e)
            }
        }
    }

    private fun handleBlockTask(payload: org.json.JSONObject?) {
        if (credentialStore.isSelfUnlockPending()) {
            Log.w(TAG, "BlockDevice task received but self-unlock is pending - ignoring stale block")
            return
        }

        val blockMessage = readPayloadString(
            payload,
            "message",
            "Message",
            "messageText",
            "MessageText",
            "reason",
            "Reason"
        ) ?: appContext.getString(R.string.block_default_message)
        val unlockCode = readPayloadString(
            payload,
            "offlineUnlockCode",
            "OfflineUnlockCode",
            "unlockCode",
            "UnlockCode"
        )

        credentialStore.saveBlockState(true)
        credentialStore.saveBlockMessage(blockMessage)
        if (!unlockCode.isNullOrBlank()) {
            credentialStore.saveUnlockCode(unlockCode)
        } else {
            credentialStore.clearUnlockCode()
        }

        appContext.sendBroadcast(
            Intent(ACTION_BLOCK_TERMINAL).apply { `package` = appContext.packageName }
        )
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            appContext.startActivity(
                Intent(appContext, BlockedActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }
        Log.w(TAG, "BlockDevice task applied unlockCode=${!unlockCode.isNullOrBlank()}")
    }

    private fun handleUnblockTask() {
        credentialStore.saveBlockState(false)
        credentialStore.clearBlockMessage()
        credentialStore.clearUnlockCode()
        appContext.sendBroadcast(
            Intent(ACTION_UNBLOCK_TERMINAL).apply { `package` = appContext.packageName }
        )
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            BlockedActivity.instance?.finish()
        }
        Log.i(TAG, "UnblockDevice task applied")
    }

    private fun handleDeleteApplicationTask(taskId: String, payload: org.json.JSONObject?) {
        val packageName = readPayloadString(
            payload,
            "packageName",
            "PackageName",
            "package",
            "Package",
            "pkg",
            "Pkg",
            "applicationId",
            "ApplicationId"
        )
        if (packageName.isNullOrBlank()) {
            Log.w(TAG, "DeleteApplication task missing package name")
            publishTaskAck(taskId, false, "DeleteApplication missing packageName")
            return
        }

        if (packageName == appContext.packageName) {
            Log.w(TAG, "DeleteApplication refused for active launcher package: $packageName")
            publishTaskAck(taskId, false, "Refusing to uninstall active launcher package")
            return
        }

        if (!isPackageInstalled(packageName)) {
            Log.i(TAG, "DeleteApplication package is not installed, reporting success: $packageName")
            publishTaskAck(taskId, true)
            publishFullStatusReport()
            return
        }

        Log.i(TAG, "DeleteApplication task uninstalling $packageName")
        uninstallPackage(packageName) { success, error ->
            publishTaskAck(taskId, success, error)
        }
    }

    private fun readPayloadString(payload: org.json.JSONObject?, vararg keys: String): String? {
        if (payload == null) return null
        for (key in keys) {
            val value = payload.optString(key, "")
            if (value.isNotBlank()) return value.trim()
        }
        return null
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            appContext.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun showDisplayMessage(payload: org.json.JSONObject?) {
        val text = readPayloadString(
            payload,
            "messageText",
            "MessageText",
            "message",
            "Message",
            "text",
            "Text"
        ) ?: ""
        if (text.isBlank()) {
            Log.w(TAG, "DisplayMessage task missing message text")
            return
        }
        appContext.startActivity(
            Intent(appContext, OperatorMessageActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_MESSAGE_TEXT, text)
            }
        )
    }

    private fun handleConfigResponseMessage(payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            val payloadText = String(payload, Charsets.UTF_8)
            val json = org.json.JSONObject(payloadText)
            val count = json.optJSONArray("Configurations")?.length()
                ?: json.optJSONArray("configurations")?.length()
                ?: 0
            Log.i(
                TAG,
                "config/response received from Global Connect bytes=${payload.size} configurations=$count " +
                    "upToDate=${json.opt("UpToDate") ?: json.opt("upToDate") ?: false} preview=${payloadText.take(500)}"
            )
            if (ParamManager.isDownloadInProgress()) {
                ParamManager.onConfigResponse(appContext, payloadText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse config/response message: ${e.message}", e)
        }
    }

    /** Starts the connection process. Does nothing if already connected, shut down, or a
     *  connection attempt is already in progress (e.g. triggered by onAvailable callback). */
    fun connect() {
        if (isConnected || isShuttingDown || isConnecting.get()) return
        if (!hasActiveNetwork()) {
            networkAvailable = false
            TmsTaskStatus.disconnected("TMS offline: no active network ($termId)")
            Log.d(TAG, "MQTT connection paused: no active cellular, Ethernet, or Wi-Fi network")
            return
        }
        networkAvailable = true
        TmsTaskStatus.connecting(termId)
        scheduleReconnect(backoffIndex = 0)
    }

    /**
     * Ensures a manual operation has a live IoT connection before it publishes.
     * A pending reconnect backoff is cancelled so the operator does not have to
     * wait several minutes for the next automatic attempt.
     */
    suspend fun ensureConnected(timeoutMillis: Long = 30_000L): TmsConnectionStatus {
        if (isConnected) return TmsTaskStatus.connection.value

        val startedAt = System.currentTimeMillis()
        while (!::appContext.isInitialized) {
            if (System.currentTimeMillis() - startedAt >= timeoutMillis) {
                return TmsTaskStatus.connection.value
            }
            delay(100)
        }

        if (!requestImmediateConnection()) {
            return TmsTaskStatus.connection.value
        }
        if (isConnected) return TmsTaskStatus.connection.value

        val elapsed = System.currentTimeMillis() - startedAt
        val remaining = (timeoutMillis - elapsed).coerceAtLeast(1L)
        return withTimeoutOrNull(remaining) {
            TmsTaskStatus.connection.first { status ->
                status.connected ||
                    status.severity == TmsStatusSeverity.ERROR ||
                    (!networkAvailable && status.severity == TmsStatusSeverity.WARNING)
            }
        } ?: TmsTaskStatus.connection.value
    }

    private fun requestImmediateConnection(): Boolean {
        if (isConnected) return true
        if (!hasActiveNetwork()) {
            networkAvailable = false
            TmsTaskStatus.disconnected("TMS offline: no active network ($termId)")
            Log.w(TAG, "Manual IoT connection request rejected: no active network")
            return false
        }

        networkAvailable = true
        if (isShuttingDown) {
            isShuttingDown = false
            consecutiveAuthFailures = 0
            credentialStore.clearLegacyMqttCredentials()
        }

        if (connectionAttemptActive.get()) {
            TmsTaskStatus.connecting(termId, "Waiting for active IoT connection")
            Log.i(TAG, "Manual IoT connection request is waiting for the active attempt")
            return true
        }

        // Invalidate and cancel a coroutine that is sleeping in exponential backoff.
        // Its finally block must not release the flag owned by the new generation.
        connectGeneration++
        cancelReconnectJob()
        isConnecting.set(false)
        TmsTaskStatus.connecting(termId, "Connecting to IoT for update")
        Log.i(TAG, "Manual update requested an immediate IoT connection")
        scheduleReconnect(backoffIndex = 0)
        return true
    }

    /** Gracefully disconnects and stops all reconnect attempts. */
    fun disconnect() {
        isShuttingDown = true
        isConnecting.set(false)
        cancelReconnectJob()
        activeClientSequence = 0L
        mqttClient?.disconnect()
        mqttClient = null
        isConnected = false
        TmsTaskStatus.disconnected("TMS disconnected ($termId)")
        Log.i(TAG, "Disconnected and shut down")
    }

    /**
     * Resets the auth-failure halt state and restarts the connection loop.
     * Called when the user taps "Retry" on the "Terminal not registered" dialog.
     */
    fun resume() {
        isShuttingDown = false
        consecutiveAuthFailures = 0
        credentialStore.clearLegacyMqttCredentials()
        Log.i(TAG, "Resuming MQTT connection after user retry with existing AWS IoT credentials")
        TmsTaskStatus.connecting(termId)
        scheduleReconnect(backoffIndex = 0)
    }

    /**
     * Single entry point for all connection scheduling.
     *
     * Always cancels any pending reconnect job first, then starts a new one.
     * The [isConnecting] AtomicBoolean prevents concurrent connection attempts
     * from multiple callers (disconnect listener, network callback, external API).
     * [connectGeneration] ensures a stale finally-block from a cancelled job does
     * not clear the flag for a newer job.
     */
    private fun scheduleReconnect(backoffIndex: Int) {
        if (isShuttingDown) return
        if (!hasActiveNetwork()) {
            networkAvailable = false
            isConnecting.set(false)
            TmsTaskStatus.disconnected("TMS offline: no active network ($termId)")
            Log.d(TAG, "MQTT reconnect paused: no active network")
            return
        }
        networkAvailable = true
        if (!isConnecting.compareAndSet(false, true)) {
            Log.d(TAG, "MQTT connection attempt already in progress")
            return
        }
        cancelReconnectJob()
        val myGeneration = ++connectGeneration
        reconnectJob = managerScope.launch {
            try {
                connectWithBackoff(backoffIndex)
            } finally {
                // Only release the flag if this is still the active generation.
                // A newer scheduleReconnect call will have incremented the generation
                // and taken ownership of isConnecting before this finally runs.
                if (connectGeneration == myGeneration) isConnecting.set(false)
            }
        }
    }

    // ── Connection loop ───────────────────────────────────────────────────────

    private suspend fun connectWithBackoff(backoffIndex: Int) {
        var attempt = backoffIndex
        while (!isShuttingDown && !isConnected) {
            if (!hasActiveNetwork()) {
                networkAvailable = false
                TmsTaskStatus.disconnected("TMS offline: no active network ($termId)")
                Log.d(TAG, "MQTT reconnect loop paused until network availability callback")
                return
            }
            val delayMs = backoffDelay(attempt)
            if (delayMs > 0) {
                Log.d(TAG, "Waiting ${delayMs} ms before connect attempt ${attempt + 1}")
                delay(delayMs)
            }
            if (isShuttingDown || isConnected) break
            if (!hasActiveNetwork()) {
                networkAvailable = false
                TmsTaskStatus.disconnected("TMS offline: no active network ($termId)")
                Log.d(TAG, "MQTT reconnect cancelled during backoff: network is unavailable")
                return
            }

            try {
                connectionAttemptActive.set(true)
                try {
                    attemptConnect()
                } finally {
                    connectionAttemptActive.set(false)
                }
                return  // success — disconnect listener will call scheduleReconnect if dropped
            } catch (e: CancellationException) {
                throw e  // propagate cancellation
            } catch (e: Exception) {
                Log.w(TAG, "Connect attempt failed: ${connectionFailureDescription(e)}")
                if (isShuttingDown) break
                if (!hasActiveNetwork()) {
                    networkAvailable = false
                    TmsTaskStatus.disconnected("TMS offline: no active network ($termId)")
                    Log.d(TAG, "MQTT retries paused after network loss")
                    return
                }
                val nextAttempt = min(attempt + 1, BACKOFF_STEPS_MS.size - 1)
                TmsTaskStatus.connectionFailed(e, backoffDelay(nextAttempt))
                attempt = nextAttempt
            }
        }
    }

    /**
     * Single connection attempt: provision/load AWS IoT certificate, MQTT CONNECT, then subscribe.
     * Throws on any failure so [connectWithBackoff] can handle retry.
     *
     * HiveMQ completes the ConnAck future **exceptionally** (Mqtt3ConnAckException wrapped in
     * ExecutionException) for any non-SUCCESS return code, so the CONNACK return code must be
     * read from the caught exception rather than from a returned value.
     *
     * The disconnected listener only schedules a reconnect when the client was previously
     * connected (isConnected == true before the drop). For rejected CONNECT attempts the
     * listener fires too, but we guard it with wasConnected so the connectWithBackoff loop
     * remains the sole owner of the retry logic.
     */
    private suspend fun attemptConnect() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Attempting MQTT connection to $brokerHost:${TMSFunc.mqttCfg.mqtt_port}…")

        TmsTaskStatus.connecting(termId)
        val keyManagerFactory = awsIotCertificateStore.getOrProvision(termId)
        val attemptSequence = clientSequence.incrementAndGet()
        activeClientSequence = attemptSequence
        val startedAtNanos = System.nanoTime()

        // Build a fresh client for each attempt — HiveMQ clients are not reusable after disconnect.
        //
        // Disconnect listener guard — wasConnected:
        //   The listener fires for BOTH genuine mid-session drops AND rejected CONNECT attempts.
        //   We only want to schedule a reconnect for genuine drops (wasConnected == true).
        //   For CONNECT rejections, connectWithBackoff owns the retry loop.
        //
        // isConnecting reset:
        //   On a genuine drop we reset isConnecting so that scheduleReconnect() can proceed.
        //   On a CONNECT rejection (wasConnected == false) isConnecting stays true; the
        //   connectWithBackoff loop is still running and will retry — no new job is needed.
        val client = buildAwsIotMqttClient(brokerHost, termId, keyManagerFactory) { event ->
            if (activeClientSequence != attemptSequence) {
                Log.d(TAG, "Ignoring disconnect from stale MQTT client attempt $attemptSequence")
                return@buildAwsIotMqttClient
            }
            val wasConnected = isConnected
            isConnected = false
            if (isShuttingDown || !wasConnected) {
                Log.w(TAG, "Disconnected (wasConnected=$wasConnected, shuttingDown=$isShuttingDown): ${event.cause.message}")
                return@buildAwsIotMqttClient
            }
            // Genuine mid-session drop — release the connecting flag and schedule reconnect.
            activeClientSequence = 0L
            isConnecting.set(false)
            Log.w(TAG, "Connection dropped: ${event.cause.message}. Scheduling reconnect.")
            TmsTaskStatus.disconnected("TMS connection dropped ($termId)")
            scheduleReconnect(backoffIndex = 1)  // start from 5 s, not immediate
        }
        mqttClient = client

        var connectionEstablished = false
        try {
            val connAck = connectAwsIotMqttClient(client)
                .get()

            // connectAwsIotMqttClient().get() is a BLOCKING call — coroutine cancellation cannot
            // interrupt it mid-flight. Check whether our coroutine is still the active one
            // before claiming the connection; if not, disconnect the ghost and bail out.
            if (!isActive || isShuttingDown || activeClientSequence != attemptSequence) {
                Log.w(TAG, "MQTT attempt $attemptSequence is no longer active; disconnecting it")
                client.disconnect()
                return@withContext
            }

            // Only SUCCESS reaches here — HiveMQ throws ExecutionException for all error codes.
            connectionEstablished = true
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
            Log.i(TAG, "MQTT connected in ${elapsedMs}ms. Session present: ${connAck.isSessionPresent}")
            // Clear the reference without cancelling — we are the reconnect job and will
            // complete naturally. This ensures cancelReconnectJob() in scheduleReconnect()
            // does not try to cancel a completed job on the next reconnect cycle.
            reconnectJob = null
            consecutiveAuthFailures = 0
            isConnected = true
            TmsTaskStatus.connected(termId)
            subscribeToTopics(client)
            flushPendingTaskAcks()

            // If an offline self-unlock was performed while disconnected, publish blk=0
            // immediately — before the server has a chance to re-deliver its persistent
            // block command. The notification handler also guards against accepting a
            // stale block while selfUnlockPending is true (belt-and-suspenders).
            if (credentialStore.isSelfUnlockPending()) {
                Log.i(TAG, "Self-unlock pending on connect — publishing blk=0 immediately")
                publishSelfUnlock()
            }

            // Deliver any status report that failed to publish while disconnected
            // (e.g. report_status command received just before the link dropped).
            if (pendingStatusPublish) {
                Log.i(TAG, "Pending status publish detected on reconnect — sending now")
                publishFullStatusReport()
            }

            // On connect: apply LauncherConfig if missing, then request version info.
            // verreq is only sent when a config was just downloaded (first provisioning)
            // so we don't re-download 60 MB APKs on every reconnect when apps are current.
            // Subsequent version checks are triggered by triggerLauncherConfigDownload()
            // whenever the server pushes a new config assignment.
            managerScope.launch(Dispatchers.IO) {
                val configWasDownloaded = LauncherConfigManager.checkAndDownloadIfMissing(appContext)
                if (configWasDownloaded) publishVersionRequest()
            }

        } catch (e: ExecutionException) {
            val connAckEx = e.cause as? Mqtt3ConnAckException
            val code = connAckEx?.mqttMessage?.returnCode
            val isAuthenticationFailure =
                code == Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD ||
                    code == Mqtt3ConnAckReturnCode.NOT_AUTHORIZED
            var certificateRefreshed = false

            if (isAuthenticationFailure) {
                try {
                    awsIotCertificateStore.refresh(termId)
                    certificateRefreshed = true
                    Log.w(TAG, "MQTT authentication failed ($code); replacement AWS IoT certificate downloaded.")
                } catch (refreshError: Exception) {
                    Log.w(TAG, "MQTT authentication failed ($code); no replacement certificate available: " +
                        refreshError.message)
                }
            }

            if (isAuthenticationFailure) {

                credentialStore.clearLegacyMqttCredentials()
                consecutiveAuthFailures = if (certificateRefreshed) 0 else consecutiveAuthFailures + 1

                if (consecutiveAuthFailures >= 2) {
                    // Two consecutive auth rejections after fresh AWS IoT certificate provisioning
                    // means the TermID is not provisioned on the TMS server.
                    Log.e(TAG, "Terminal not registered in TMS — halting reconnect after " +
                        "$consecutiveAuthFailures consecutive auth failures.")
                    isShuttingDown = true  // stop the connectWithBackoff loop
                    TmsTaskStatus.terminalNotRegistered(termId)
                    appContext.sendBroadcast(
                        Intent(ACTION_TERMINAL_NOT_REGISTERED).apply {
                            `package` = appContext.packageName
                        }
                    )
                } else {
                    Log.w(TAG, "MQTT auth rejected ($code) — attempt $consecutiveAuthFailures. " +
                        "Retrying with the current or newly provisioned certificate.")
                }
            } else {
                Log.w(TAG, "MQTT CONNECT failed: $code (${e.cause?.message})")
            }
            throw e  // re-throw so connectWithBackoff can apply backoff / exit loop
        } finally {
            if (!connectionEstablished && activeClientSequence == attemptSequence) {
                activeClientSequence = 0L
                if (mqttClient === client) mqttClient = null
            }
        }
    }


    // ── Subscriptions ─────────────────────────────────────────────────────────

    private fun subscribeToTopics(client: Mqtt3AsyncClient) {
        val notifyTopic = termNotifyTopic(termId)

        client.subscribeWith()
            .topicFilter(notifyTopic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { message ->
                notificationHandler.handleMessage(
                    topic   = message.topic.toString(),
                    payload = message.payloadAsBytes
                )
            }
            .send()
            .whenComplete { _, err ->
                if (err != null) Log.e(TAG, "Subscribe $notifyTopic failed: ${err.message}")
                else Log.i(TAG, "Subscribed: $notifyTopic (QoS 1)")
            }

        client.subscribeWith()
            .topicFilter(BROADCAST_NOTIFY_TOPIC)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { message ->
                notificationHandler.handleMessage(
                    topic   = message.topic.toString(),
                    payload = message.payloadAsBytes
                )
            }
            .send()
            .whenComplete { _, err ->
                if (err != null) Log.e(TAG, "Subscribe $BROADCAST_NOTIFY_TOPIC failed: ${err.message}")
                else Log.i(TAG, "Subscribed: $BROADCAST_NOTIFY_TOPIC (QoS 1)")
            }

        // cmd topic — server-initiated commands (e.g. report_status).
        // QoS 1 so the command is queued if the terminal briefly disconnects
        // and delivered on the next connect.
        val cmdTopic = termCmdTopic(termId)
        client.subscribeWith()
            .topicFilter(cmdTopic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { message ->
                handleCmdMessage(message.payloadAsBytes)
            }
            .send()
            .whenComplete { _, err ->
                if (err != null) Log.e(TAG, "Subscribe $cmdTopic failed: ${err.message}")
                else Log.i(TAG, "Subscribed: $cmdTopic (QoS 1)")
            }

        // easy topic — server pushes Easy Download task packets to the terminal.
        // QoS 1 so notifications are delivered even after a brief disconnect.
        val taskTopic = termTaskTopic(termId)
        client.subscribeWith()
            .topicFilter(taskTopic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { message ->
                handleTaskMessage(message.payloadAsBytes)
            }
            .send()
            .whenComplete { _, err ->
                if (err != null) Log.e(TAG, "Subscribe $taskTopic failed: ${err.message}")
                else Log.i(TAG, "Subscribed: $taskTopic (QoS 1)")
            }

        // paramres topic — broker replies {"ok":true} or {"ok":false,"err":"..."} after
        // processing a paramreq.  QoS 1 so the ack is not lost on a brief disconnect.
        val configResponseTopic = termConfigResponseTopic(termId)
        client.subscribeWith()
            .topicFilter(configResponseTopic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { message ->
                handleConfigResponseMessage(message.payloadAsBytes)
            }
            .send()
            .whenComplete { _, err ->
                if (err != null) Log.e(TAG, "Subscribe $configResponseTopic failed: ${err.message}")
                else Log.i(TAG, "Subscribed: $configResponseTopic (QoS 1)")
            }

        val licenseResponseTopic = termApplicationLicenseResponseTopic(termId)
        client.subscribeWith()
            .topicFilter(licenseResponseTopic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { message ->
                ApplicationLicenseBroker.complete(String(message.payloadAsBytes, Charsets.UTF_8))
            }
            .send()
            .whenComplete { _, err ->
                if (err != null) Log.e(TAG, "Subscribe $licenseResponseTopic failed: ${err.message}")
                else Log.i(TAG, "Subscribed: $licenseResponseTopic (QoS 1)")
            }

    }

    /**
     * Handles a message received on the terminal's paramres topic.
     * Expected payload: UTF-8 JSON, e.g. {"ok":true} or {"ok":false,"err":"unavailable"}
     */
    private fun handleParamResMessage(payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            val json = org.json.JSONObject(String(payload, Charsets.UTF_8))
            if (json.optBoolean("ok", false)) {
                Log.i(TAG, "paramres: ok=true — triggering HTTP param download")
                ParamManager.onParamReady(appContext)
            } else {
                val err = json.optString("err", "server error")
                Log.w(TAG, "paramres: ok=false, err='$err'")
                ParamManager.onParamFailed(appContext, err)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse paramres message: ${e.message}")
        }
    }

    /**
     * Handles a version-info response from the server on the verinfo topic.
     * Compares server-assigned progApp version against what is installed on the device.
     * If a mismatch is detected, triggers the HTTP APK download and install.
     */
    private fun handleVerInfoMessage(payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            val result = TermVersionChecker.check(appContext, payload)
            val summary = result.appsToUpdate.joinToString { "${it.packageId} installed=${it.installedVersion ?: "none"} server=${it.serverVersion}" }
            Log.i(TAG, "verinfo: needsAppUpdate=${result.needsAppUpdate} updates=[$summary]")
            if (result.needsAppUpdate) {
                AppUpdateManager.downloadAndInstallIfNeeded(appContext, result) {
                    publishFullStatusReport()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle verinfo message: ${e.message}", e)
        }
    }

    /**
     * Handles a message received on the terminal's cmd topic.
     * Expected payload: UTF-8 JSON, e.g. {"cmd":"report_status"}
     *
     * Supported commands:
     *   report_status  — publish a full status report immediately, including os, mdl, apps.
     *   uninstall_app  — silently uninstall the package named in the "pkg" field.
     */
    private fun handleCmdMessage(payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            val json = org.json.JSONObject(String(payload, Charsets.UTF_8))
            when (val cmd = json.optString("cmd")) {
                "report_status" -> {
                    Log.i(TAG, "report_status command received — publishing full status report")
                    publishFullStatusReport()
                }
                "uninstall_app" -> {
                    val pkg = json.optString("pkg")
                    if (pkg.isNullOrBlank()) {
                        Log.w(TAG, "uninstall_app command missing 'pkg' field — ignored")
                    } else {
                        Log.i(TAG, "uninstall_app command received — uninstalling $pkg")
                        uninstallPackage(pkg)
                    }
                }
                "remote_start" -> {
                    try {
                        RemoteControlConfig.fromJson(json, termId)
                        Log.i(TAG, "Kinesis remote_start received; launching permission activity")
                        val intent = Intent(appContext, RemoteControlPermissionActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            putExtra(RemoteControlPermissionActivity.EXTRA_CONFIG_JSON, json.toString())
                            putExtra(RemoteControlPermissionActivity.EXTRA_TERMINAL_ID, termId)
                        }
                        appContext.startActivity(intent)
                    } catch (e: Exception) {
                        Log.w(TAG, "remote_start invalid for Kinesis WebRTC: ${e.message}")
                    }
                    return
                }
                "remote_stop" -> {
                    Log.i(TAG, "remote_stop received — stopping remote control session")
                    val intent = Intent(appContext, RemoteControlService::class.java).apply {
                        action = RemoteControlService.ACTION_STOP
                    }
                    appContext.startService(intent)
                }
                else -> Log.w(TAG, "Unknown cmd message: '$cmd'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cmd message: ${e.message}")
        }
    }

    /**
     * Silently uninstalls a package using the Nexgo Platform SDK.
     * This runs under system privileges — no dialog is shown to the user and no
     * REQUEST_DELETE_PACKAGES permission is required.
     *
     * On success the [OnAppOperatListener] callback publishes a fresh app inventory
     * so the TMS server sees the updated list immediately.
     */
    private fun uninstallPackage(packageName: String, onComplete: ((Boolean, String?) -> Unit)? = null) {
        val completionDelivered = AtomicBoolean(false)
        fun complete(success: Boolean, error: String? = null) {
            if (completionDelivered.compareAndSet(false, true)) {
                onComplete?.invoke(success, error)
            }
        }

        try {
            val platform = com.nexgo.oaf.apiv3.APIProxy.getDeviceEngine(appContext).platform
            val result = platform.uninstallApp(
                packageName,
                object : com.nexgo.oaf.apiv3.OnAppOperatListener {
                    override fun onOperatResult(res: Int) {
                        if (res == com.nexgo.oaf.apiv3.SdkResult.Success) {
                            Log.i(TAG, "Nexgo SDK uninstall success: $packageName — refreshing app inventory")
                            publishFullStatusReport()
                            complete(true)
                        } else {
                            val error = "Nexgo SDK uninstall failed for $packageName: result=$res"
                            Log.e(TAG, error)
                            complete(false, error)
                        }
                    }
                }
            )
            if (result != com.nexgo.oaf.apiv3.SdkResult.Success) {
                val error = "uninstallApp() returned $result immediately for $packageName"
                Log.e(TAG, error)
                complete(false, error)
            } else {
                Log.i(TAG, "Uninstall initiated for $packageName via Nexgo SDK")
                if (onComplete == null) complete(true)
            }
        } catch (e: Exception) {
            val error = "Failed to uninstall $packageName: ${e.message}"
            Log.e(TAG, error)
            complete(false, error)
        }
    }

    // ── Parameter download request ────────────────────────────────────────────

    /**
     * Publishes a paramreq to the MQTT broker, asking the server to prepare and
     * confirm availability of the terminal's parameter file of the given type.
     *
     * The broker will reply on the paramres topic; [handleParamResMessage] dispatches
     * the result to [ParamManager.onParamReady] or [ParamManager.onParamFailed].
     *
     * QoS 1 — the request must reach the broker reliably; the server only replies
     * once and does not re-send paramres if the terminal reconnects.
     *
     * @param fileType "json" (default) or "dat".
     * @param applicationIds Optional TMS application identifiers to request, e.g. PAYMENT_APP.
     * @return true if the message was submitted to the MQTT client, false if not connected.
     */
    fun publishParamReq(fileType: String = "json", applicationIds: List<String> = emptyList()): Boolean {
        val sourcePayload = org.json.JSONObject().apply {
            put("ft", fileType.replace("\"", ""))
            if (applicationIds.isNotEmpty()) {
                put("applicationIds", org.json.JSONArray(applicationIds))
            }
        }
        return publishConfigRequest(sourcePayload)
        @Suppress("UNREACHABLE_CODE")
        val client = mqttClient!!
        if (!isConnected) {
            Log.w(TAG, "publishParamReq: not connected — cannot send paramreq")
            return false
        }
        val payload = """{"ft":"${fileType.replace("\"", "")}"}""".toByteArray(Charsets.UTF_8)
        client.publishWith()
            .topic(termParamReqTopic(termId))
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(payload)
            .send()
            .whenComplete { _, err ->
                if (err != null) Log.w(TAG, "paramreq publish failed: ${err.message}")
                else Log.d(TAG, "paramreq published → ${termParamReqTopic(termId)}")
            }
        return true
    }

    // ── Version request ───────────────────────────────────────────────────────

    /**
     * Publishes an empty payload to the verreq topic, asking the server to send the
     * current assigned version info (progApp, sysSet, logos) on the verinfo topic.
     * The server response is handled by [handleVerInfoMessage].
     *
     * @return true if the message was submitted to the MQTT client, false if not connected.
     */
    private fun publishConfigRequest(sourcePayload: org.json.JSONObject? = null): Boolean {
        val client = mqttClient
        if (client == null || !isConnected) {
            Log.w(TAG, "publishConfigRequest: not connected")
            return false
        }
        val json = org.json.JSONObject()
        val applications = sourcePayload?.optJSONArray("applications")
            ?: sourcePayload?.optJSONArray("Applications")
        val applicationIds = sourcePayload?.optJSONArray("applicationIds")
        val fileType = readPayloadString(sourcePayload, "ft", "fileType", "FileType")
        if (applications != null) json.put("applications", applications)
        if (applicationIds != null) json.put("applicationIds", applicationIds)
        if (!fileType.isNullOrBlank()) json.put("ft", fileType)
        json.put("requestedAt", Instant.now().toString())
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        Log.i(
            TAG,
            "Publishing config/request topic=${termConfigRequestTopic(termId)} payload=$json " +
                "isConnected=$isConnected"
        )
        client.publishWith()
            .topic(termConfigRequestTopic(termId))
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(payload)
            .send()
            .whenComplete { _, err ->
                if (err != null) Log.w(TAG, "config/request publish failed: ${err.message}")
                else Log.d(TAG, "config/request published -> ${termConfigRequestTopic(termId)}")
            }
        return true
    }

    fun publishVersionRequest(): Boolean {
        return publishConfigRequest(null)
        @Suppress("UNREACHABLE_CODE")
        val client = mqttClient!!
        if (!isConnected) {
            Log.w(TAG, "publishVersionRequest: not connected — cannot send verreq")
            return false
        }
        client.publishWith()
            .topic(termVerReqTopic(termId))
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(ByteArray(0))
            .send()
            .whenComplete { _, err ->
                if (err != null) Log.w(TAG, "verreq publish failed: ${err.message}")
                else Log.d(TAG, "verreq published → ${termVerReqTopic(termId)}")
            }
        return true
    }

    // ── Heartbeat publish (called by TmsStatusWorker) ─────────────────────────

    /** Publishes a lightweight JSON heartbeat payload. QoS 0 — fire and forget. */
    fun publishStatus(payload: ByteArray) {
        val client = mqttClient
        if (client == null || !isConnected) {
            Log.w(TAG, "publishStatus: not connected - heartbeat skipped")
            return
        }
        client.publishWith()
            .topic(termHeartbeatTopic(termId))
            .qos(MqttQos.AT_MOST_ONCE)
            .payload(payload)
            .send()
            .whenComplete { _, err ->
                if (err != null) Log.w(TAG, "Heartbeat publish failed: ${err.message}")
                else Log.d(TAG, "Heartbeat published -> ${termHeartbeatTopic(termId)}")
            }
    }

    fun publishTransactionReport(
        payload: org.json.JSONObject,
        onComplete: ((Boolean, String?) -> Unit)? = null,
    ): Boolean {
        val client = mqttClient
        if (client == null || !isConnected) {
            Log.w(TAG, "publishTransactionReport: not connected")
            onComplete?.invoke(false, "MQTT client is not connected")
            return false
        }

        val reportPayload = enrichTransactionReportPayload(payload)
        val topic = reportTopic(reportPayload)
        val jsonBytes = reportPayload.toString().toByteArray(Charsets.UTF_8)
        val publishBytes = gzip(jsonBytes)
        client.publishWith()
            .topic(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(publishBytes)
            .send()
            .whenComplete { _, err ->
                if (err != null) {
                    Log.w(TAG, "Transaction report publish failed topic=$topic error=${err.message}")
                    onComplete?.invoke(false, err.message ?: "Transaction report publish failed")
                } else {
                    val transactionId = reportPayload.optString("transactionId", "")
                    val batchId = reportPayload.optString("batchId", "")
                    val count = reportPayload.optJSONArray("transactions")?.length() ?: 1
                    Log.i(
                        TAG,
                        "Transaction report published -> $topic transactionId=${transactionId.ifBlank { "(none)" }} " +
                            "batchId=${batchId.ifBlank { "(none)" }} count=$count " +
                            "jsonBytes=${jsonBytes.size} gzipBytes=${publishBytes.size}"
                    )
                    onComplete?.invoke(true, null)
                }
            }
        return true
    }

    fun publishAdminRequest(
        payload: org.json.JSONObject,
        onComplete: ((Boolean, String?) -> Unit)? = null,
    ): Boolean {
        val client = mqttClient
        if (client == null || !isConnected) {
            Log.w(TAG, "publishAdminRequest: not connected")
            onComplete?.invoke(false, "MQTT client is not connected")
            return false
        }

        val requestPayload = enrichAdminRequestPayload(payload)
        val topic = termAdminRequestTopic(termId)
        val publishBytes = requestPayload.toString().toByteArray(Charsets.UTF_8)
        client.publishWith()
            .topic(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(publishBytes)
            .send()
            .whenComplete { _, err ->
                if (err != null) {
                    Log.w(TAG, "Admin request publish failed topic=$topic error=${err.message}")
                    onComplete?.invoke(false, err.message ?: "Admin request publish failed")
                } else {
                    Log.i(
                        TAG,
                        "Admin request published -> $topic requestId=${requestPayload.optString("requestId", "(none)")} " +
                            "requestType=${requestPayload.optString("requestType", "(none)")} bytes=${publishBytes.size}"
                    )
                    onComplete?.invoke(true, null)
                }
            }
        return true
    }

    fun publishApplicationLicenseRequest(
        payload: org.json.JSONObject,
        onComplete: ((Boolean, String?) -> Unit)? = null,
    ): Boolean {
        val client = mqttClient
        if (client == null || !isConnected) {
            onComplete?.invoke(false, "MQTT client is not connected")
            return false
        }
        val topic = termApplicationLicenseRequestTopic(termId)
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        client.publishWith()
            .topic(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(bytes)
            .send()
            .whenComplete { _, err ->
                onComplete?.invoke(err == null, err?.message)
            }
        return true
    }

    private fun reportTopic(payload: org.json.JSONObject): String {
        val messageType = payload.optString("messageType", payload.optString("type", ""))
        return if (messageType.equals("settlement", ignoreCase = true)
            || messageType.equals("settlement_report", ignoreCase = true)
            || messageType.equals("batch_settlement", ignoreCase = true)
        ) {
            termSettlementTopic(termId)
        } else {
            termTransactionTopic(BuildConfig.GLOBAL_CONNECT_ENV, termId)
        }
    }

    private fun enrichTransactionReportPayload(payload: org.json.JSONObject): org.json.JSONObject {
        val copy = org.json.JSONObject(payload.toString())
        if (copy.optString("serialNumber").isBlank()) {
            copy.put("serialNumber", termId)
        }
        return copy
    }

    private fun enrichAdminRequestPayload(payload: org.json.JSONObject): org.json.JSONObject {
        val copy = org.json.JSONObject(payload.toString())
        if (copy.optString("type").isBlank()) copy.put("type", "admin_request")
        if (copy.optString("messageType").isBlank()) copy.put("messageType", "admin_request")
        if (copy.optString("serialNumber").isBlank()) copy.put("serialNumber", termId)
        if (copy.optString("requestedAt").isBlank()) copy.put("requestedAt", Instant.now().toString())
        return copy
    }

    private fun gzip(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(data) }
        return output.toByteArray()
    }

    /**
     * Immediately publishes a one-shot status report with blk=0 to inform the TMS
     * server that the terminal performed a self-unlock by offline code.
     * Called by BlockedActivity right before it clears the local block state.
     * If the MQTT client is not connected the TmsStatusWorker will send blk=0
     * on its next run via the isSelfUnlockPending() flag.
     */
    fun publishSelfUnlock() {
        val client = mqttClient
        if (client == null || !isConnected) {
            Log.w(TAG, "publishSelfUnlock: not connected — blk=0 deferred to next status report")
            return
        }
        val payload = org.json.JSONObject()
            .apply { put("s", 1); put("blk", 0) }
            .toString()
            .toByteArray(Charsets.UTF_8)
        client.publishWith()
            .topic(termStatusTopic(termId))
            .qos(MqttQos.AT_MOST_ONCE)
            .payload(payload)
            .send()
            .whenComplete { _, err ->
                if (err != null) {
                    Log.w(TAG, "Self-unlock blk=0 publish failed: ${err.message}")
                } else {
                    Log.i(TAG, "Self-unlock blk=0 published → ${termStatusTopic(termId)}")
                    // Clear the pending flag now that the message is in flight.
                    // TmsStatusWorker checks this flag too — clearing it here prevents
                    // a redundant blk=0 in the next periodic report.
                    credentialStore.saveSelfUnlockPending(false)
                }
            }
    }

    /**
     * Publishes a full status report on-demand (os, mdl, apps, sig, bat, ver, blk).
     *
     * Called when:
     *   1. The TMS broker sends a `report_status` command via the cmd topic
     *      (triggered on connect when app inventory is missing or older than 7 days).
     *   2. An app is installed or uninstalled ([InstallReceiver] calls this directly).
     *
     * Also callable from outside this object (e.g. InstallReceiver) so the app inventory
     * is pushed to the server immediately when it changes, without waiting for the weekly
     * scheduled refresh cycle.
     */
    fun publishFullStatusReport(onComplete: ((Boolean, String?) -> Unit)? = null) {
        val client = mqttClient
        if (client == null || !isConnected) {
            Log.w(TAG, "publishFullStatusReport: not connected — flagging for retry on reconnect")
            pendingStatusPublish = true
            onComplete?.invoke(false, "MQTT client is not connected")
            return
        }
        val payload = TmsStatusWorker.buildFullPayload(appContext)
        client.publishWith()
            .topic(termStatusTopic(termId))
            .qos(MqttQos.AT_MOST_ONCE)
            .payload(payload)
            .send()
            .whenComplete { _, err ->
                if (err != null) {
                    Log.w(TAG, "Full status report publish failed: ${err.message} — flagging for retry on reconnect")
                    pendingStatusPublish = true
                    onComplete?.invoke(false, err.message ?: "Full status report publish failed")
                } else {
                    pendingStatusPublish = false
                    Log.i(TAG, "Full status report published → ${termStatusTopic(termId)}")
                    onComplete?.invoke(true, null)
                }
            }
    }

    private fun publishTaskAck(
        taskId: String,
        success: Boolean,
        errorMessage: String? = null,
        statusOverride: String? = null,
        statusMessage: String? = null,
        queueWhenOffline: Boolean = true
    ) {
        val client = mqttClient
        if (client == null || !isConnected) {
            if (queueWhenOffline && ::appContext.isInitialized) {
                PendingTaskAckStore.enqueue(appContext, taskId, success, errorMessage, statusOverride, statusMessage)
            }
            Log.w(TAG, "publishTaskAck: not connected - taskId=$taskId success=$success queued=$queueWhenOffline")
            return
        }
        val now = Instant.now().toString()
        val status = statusOverride ?: if (success) "completed" else "failed"
        val result = org.json.JSONObject().apply {
            if (success) put("message", "Task completed")
            else if (!errorMessage.isNullOrBlank()) put("message", errorMessage)
        }
        val payload = org.json.JSONObject()
            .put("taskId", taskId)
            .put("taskRecordId", taskId)
            .put("status", status)
            .put("success", success)
            .put("errorMessage", if (errorMessage.isNullOrBlank()) org.json.JSONObject.NULL else errorMessage)
            .put("statusMessage", if (statusMessage.isNullOrBlank()) org.json.JSONObject.NULL else statusMessage)
            .put("result", result)
            .put("acknowledgedAt", now)
            .put("ts", now)
            .toString()
            .toByteArray(Charsets.UTF_8)
        client.publishWith()
            .topic(termTaskAckTopic(termId))
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(payload)
            .send()
            .whenComplete { _, err ->
                if (err != null) {
                    Log.w(TAG, "Task ACK publish failed for taskId=$taskId: ${err.message}")
                    if (queueWhenOffline) PendingTaskAckStore.enqueue(appContext, taskId, success, errorMessage, statusOverride, statusMessage)
                }
                else Log.d(TAG, "Task ACK published -> taskId=$taskId success=$success")
            }
    }

    private fun flushPendingTaskAcks() {
        val pending = PendingTaskAckStore.takeAll(appContext)
        if (pending.isEmpty()) return
        Log.i(TAG, "Publishing ${pending.size} queued task ACK(s)")
        pending.forEach { ack ->
            publishTaskAck(ack.taskId, ack.success, ack.errorMessage, ack.status, ack.statusMessage)
        }
    }

    /**
     * Publishes an Easy Download acknowledgment to the server.
     * QoS 1 — the server must receive the status update reliably.
     * If not connected, the ack is lost; [EasyTaskStore] preserves state for retry
     * on the next reconnect (triggered from [subscribeToTopics] via [EasyTaskManager.handleEasyPacket]).
     */
    fun publishEasyAck(report: EasyAckReport) {
        Log.w(TAG, "publishEasyAck ignored on AWS IoT path - legacy Easy ACKs are not supported")
        return
        @Suppress("UNREACHABLE_CODE")
        val client = mqttClient!!
        if (!isConnected) {
            Log.w(TAG, "publishEasyAck: not connected — easyId=${report.easyId} status=${report.status} dropped")
            return
        }
        client.publishWith()
            .topic(termEasyAckTopic(termId))
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(report.toBytes())
            .send()
            .whenComplete { _, err ->
                if (err != null) Log.w(TAG, "EasyAck publish failed for easyId=${report.easyId}: ${err.message}")
                else Log.d(TAG, "EasyAck published → easyId=${report.easyId} status=${report.status}")
            }
    }

    /**
     * Publishes a launcher-config ACK to the server after the terminal has
     * successfully downloaded and applied a new LauncherConfig.
     *
     * The server only clears [LauncherConfigNotifyPending] on receipt of this message,
     * so the notification is persistent across MQTT service restarts until confirmed.
     *
     * If the client is not connected the ACK is dropped — the server will re-notify
     * (with a 5-minute throttle) on the next poll cycle, causing another download
     * attempt that will succeed and publish a fresh ACK.
     */
    fun publishCfgAck() {
        Log.w(TAG, "publishCfgAck ignored on AWS IoT path - config ACK is represented by task/ack")
        return
        @Suppress("UNREACHABLE_CODE")
        val client = mqttClient!!
        if (!isConnected) {
            Log.w(TAG, "publishCfgAck: not connected — cfgack dropped (server will re-notify)")
            return
        }
        client.publishWith()
            .topic(termCfgAckTopic(termId))
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(ByteArray(0))
            .send()
            .whenComplete { _, err ->
                if (err != null) Log.w(TAG, "CfgAck publish failed: ${err.message}")
                else Log.i(TAG, "CfgAck published → server will clear LauncherConfigNotifyPending")
            }
    }

    // ── HouseKeeping request ─────────────────────────────────────────────────

    /**
     * Publishes the hkreq payload built by [TmsHouseKeepingManager].
     * QoS 1 so the request is reliably delivered and the server always processes it.
     *
     * @return true if the message was submitted to the MQTT client, false if not connected.
     */
    private fun publishHkReq(payload: ByteArray): Boolean {
        Log.w(TAG, "publishHkReq ignored on AWS IoT path - legacy housekeeping payloads are not supported")
        return false
        @Suppress("UNREACHABLE_CODE")
        val client = mqttClient!!
        if (!isConnected) {
            Log.w(TAG, "publishHkReq: not connected — cannot send hkreq")
            return false
        }
        client.publishWith()
            .topic(termHkReqTopic(termId))
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(payload)
            .send()
            .whenComplete { _, err ->
                if (err != null) Log.w(TAG, "hkreq publish failed: ${err.message}")
                else Log.d(TAG, "hkreq published → ${termHkReqTopic(termId)}")
            }
        return true
    }

    // ── Server-triggered housekeeping ────────────────────────────────────────

    /**
     * Holds a deferred HouseKeeping request that arrived before LauncherConfig was applied.
     * null = no pending request. true/false = urgent flag of the deferred request.
     */
    private val _pendingHkUrgent = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)

    /**
     * Stores a HouseKeeping request to be run once LauncherConfig has been applied.
     * Called by [TmsNotificationHandler] when a notify arrives on a fresh install.
     */
    fun setPendingHouseKeeping(urgent: Boolean) {
        // If a non-urgent request is already pending, an urgent one takes priority.
        _pendingHkUrgent.updateAndGet { existing ->
            if (existing == null || urgent) urgent else existing
        }
        Log.i(TAG, "HouseKeeping deferred — awaiting LauncherConfig (urgent=$urgent)")
    }

    /**
     * Fires the deferred HouseKeeping request if one is pending.
     * Called by [LauncherConfigManager] after a config is successfully applied.
     * Also re-arms the daily alarm for tomorrow so the 5-min catch-up alarm
     * (set at startup because config wasn't applied yet) is replaced and won't
     * fire a redundant second HK cycle.
     */
    fun triggerDeferredHouseKeepingIfPending() {
        val urgent = _pendingHkUrgent.getAndSet(null) ?: return
        Log.i(TAG, "LauncherConfig applied — running deferred HouseKeeping (urgent=$urgent)")
        one.globalconnect.xtmsagent.mqtt.housekeeping.TmsHkScheduler.onDeferredHkTriggered(appContext)
        triggerHouseKeeping(urgent)
    }

    /**
     * Performs a full TMS housekeeping cycle on a background coroutine, independent
     * of any Activity lifecycle.  Called directly by [TmsNotificationHandler] when the
     * server pushes a download notification — no broadcast needed, so the cycle runs
     * even when MainActivity is in the background (user is inside a payment app, etc.).
     *
     * After the cycle completes, [ACTION_HOUSEKEEPING_COMPLETE] is broadcast so
     * MainActivity can refresh logos and the button grid if it happens to be visible.
     */
    fun triggerHouseKeeping(urgent: Boolean) {
        managerScope.launch(Dispatchers.IO) {
            val delayMs = if (urgent) 0L else (Math.random() * 300_000).toLong()
            if (delayMs > 0) {
                Log.d(TAG, "HouseKeeping: jitter delay ${delayMs}ms")
                delay(delayMs)
            }
            Log.i(TAG, "HouseKeeping: publishing hkreq (urgent=$urgent)")
            TmsHouseKeepingManager.triggerHouseKeeping(appContext) { payload ->
                publishHkReq(payload)
            }
            // Let MainActivity refresh its UI if it is currently in the foreground.
            appContext.sendBroadcast(
                Intent(ACTION_HOUSEKEEPING_COMPLETE).apply { `package` = appContext.packageName }
            )
        }
    }

    /**
     * Downloads and applies the terminal's assigned LauncherConfig on a background coroutine,
     * independent of any Activity lifecycle.
     *
     * Called by [TmsNotificationHandler] when the server pushes a LauncherConfigDl
     * notification, and also on every successful MQTT connect to ensure the terminal
     * always has its assigned config applied (covers first-provisioning and cold starts).
     *
     * A short random jitter (0–30 s) is applied unless [urgent] is true, to spread
     * load when many terminals reconnect simultaneously after a server restart.
     */
    fun triggerLauncherConfigDownload(urgent: Boolean = false) {
        managerScope.launch(Dispatchers.IO) {
            val jitterMs = if (urgent) 0L else (Math.random() * 30_000).toLong()
            if (jitterMs > 0) {
                Log.d(TAG, "LauncherConfig download: jitter delay ${jitterMs}ms")
                delay(jitterMs)
            }
            Log.i(TAG, "LauncherConfig download: running (urgent=$urgent)")
            LauncherConfigManager.downloadAndApply(appContext, urgent)
            // Config assignment may have changed the prog-app file set — request fresh verinfo.
            publishVersionRequest()
        }
    }

    // ── Network change listener ───────────────────────────────────────────────

    private fun registerNetworkCallback() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateNetworkAvailability("available")
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                updateNetworkAvailability("capabilities changed")
            }

            override fun onLost(network: Network) {
                managerScope.launch {
                    delay(250L)
                    updateNetworkAvailability("lost")
                }
            }
        })
    }

    fun publishExternalTaskAck(
        context: Context,
        taskId: String,
        success: Boolean,
        errorMessage: String? = null,
        status: String? = null,
        statusMessage: String? = null
    ) {
        if (!::appContext.isInitialized) {
            PendingTaskAckStore.enqueue(context, taskId, success, errorMessage, status, statusMessage)
            TmsMqttService.start(context)
            return
        }
        publishTaskAck(taskId, success, errorMessage, status, statusMessage)
    }

    private fun updateNetworkAvailability(reason: String) {
        val available = hasActiveNetwork()
        val wasAvailable = networkAvailable
        networkAvailable = available

        if (!available) {
            if (!wasAvailable && !isConnected && !isConnecting.get()) return
            connectGeneration++
            cancelReconnectJob()
            isConnecting.set(false)
            isConnected = false
            activeClientSequence = 0L
            val client = mqttClient
            mqttClient = null
            client?.disconnect()
            if (!isShuttingDown) {
                TmsTaskStatus.disconnected("TMS offline: no active network ($termId)")
            }
            Log.w(TAG, "Network unavailable ($reason); MQTT retries paused")
            return
        }

        if (isShuttingDown || wasAvailable || isConnected) return
        Log.i(TAG, "Network available ($reason); reconnecting to TMS immediately")
        TmsTaskStatus.connecting(termId, "Network available. Reconnecting to TMS")
        isConnecting.set(false)
        scheduleReconnect(backoffIndex = 0)
    }

    private fun hasActiveNetwork(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun cancelReconnectJob() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun connectionFailureDescription(error: Throwable): String {
        val cause = (error as? ExecutionException)?.cause ?: error
        return "${cause.javaClass.simpleName}: ${cause.message ?: "no detail"}"
    }

    private fun backoffDelay(index: Int): Long {
        val base = BACKOFF_STEPS_MS[min(index, BACKOFF_STEPS_MS.size - 1)]
        if (base == 0L) return 0L
        val jitter = (base * BACKOFF_JITTER_FRACTION * (Math.random() * 2 - 1)).toLong()
        return (base + jitter).coerceAtLeast(0L)
    }
}
