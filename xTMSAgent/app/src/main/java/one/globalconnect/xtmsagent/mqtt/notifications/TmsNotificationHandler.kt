package one.globalconnect.xtmsagent.mqtt.notifications

import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexgo.oaf.apiv3.APIProxy
import one.globalconnect.xtmsagent.BlockedActivity
import one.globalconnect.xtmsagent.R
import one.globalconnect.xtmsagent.launcher.LauncherConfigManager
import one.globalconnect.xtmsagent.mqtt.persistence.TmsCredentialStore
import one.globalconnect.xtmsagent.params.ParamManager

private const val TAG = "TmsNotifHandler"

// Bit masks for byte 0 (flags byte) — must match TmsNotifyPacket.cs on the server.
private const val FLAG_PARAM_DOWNLOAD  = 0x01  // Bit 0: parameter download pending
private const val FLAG_APP_DOWNLOAD    = 0x02  // Bit 1: application download pending
private const val FLAG_FW_UPDATE       = 0x04  // Bit 2: firmware update pending
private const val FLAG_URGENT          = 0x08  // Bit 3: no jitter — act immediately
private const val BLOCK_ACTION_MASK    = 0x30  // Bits 4-5: block action field
private const val BLOCK_ACTION_NONE    = 0x00  // 00 — no change
private const val BLOCK_ACTION_UNBLOCK = 0x10  // 01 — unblock terminal
private const val BLOCK_ACTION_BLOCK   = 0x20  // 10 — block terminal
private const val BLOCK_ACTION_REBOOT  = 0x30  // 11 — reboot device
private const val FLAG_HAS_MESSAGE     = 0x40  // Bit 6: message text follows
private const val FLAG_HAS_UNLOCK_CODE = 0x80  // Bit 7: 10-byte ASCII unlock code precedes message

// Bit masks for byte 1 (extended flags byte) — v2 protocol, always present after byte 0.
private const val EXT_FLAG_LAUNCHER_CONFIG_DL = 0x01  // Bit 0: launcher config download pending

/** Broadcast actions for block state changes. */
const val ACTION_BLOCK_TERMINAL       = "one.globalconnect.xtmsagent.ACTION_BLOCK_TERMINAL"
const val ACTION_UNBLOCK_TERMINAL     = "one.globalconnect.xtmsagent.ACTION_UNBLOCK_TERMINAL"

/**
 * Broadcast fired by TmsMqttManager when the TMS broker rejects authentication
 * repeatedly — meaning the terminal TermID is not provisioned in the TMS server.
 * MainActivity shows a blocking dialog and halts reconnection until the user retries.
 */
const val ACTION_TERMINAL_NOT_REGISTERED = "one.globalconnect.xtmsagent.ACTION_TERMINAL_NOT_REGISTERED"

/**
 * Broadcast fired when the TMS server sends an operator message.
 * The message text is carried in [EXTRA_MESSAGE_TEXT].
 * MainActivity shows it immediately in an AlertDialog.
 */
const val ACTION_SHOW_OPERATOR_MESSAGE = "one.globalconnect.xtmsagent.ACTION_SHOW_OPERATOR_MESSAGE"
const val EXTRA_MESSAGE_TEXT           = "message_text"

enum class BlockAction { NONE, BLOCK, UNBLOCK, REBOOT }

/**
 * Parsed representation of one TMS notification packet.
 */
data class TmsNotification(
    val paramDownloadPending: Boolean,
    val appDownloadPending: Boolean,
    val firmwareUpdatePending: Boolean,
    val urgent: Boolean,
    val blockAction: BlockAction,
    val unlockCode: String?,              // 10-digit ASCII offline unlock code, null if not present
    val message: String?,
    val launcherConfigDownloadPending: Boolean = false  // extended flags byte, bit 0
)

/**
 * Handles raw MQTT message bytes received on the terminal notify topics.
 *
 * Wire format (binary):
 *   Byte 0        : flags (see bit constants above)
 *   [If HasUnlockCode bit set:]
 *     Bytes 1-10  : 10 fixed-length ASCII digits (offline unlock code)
 *   [If HasMessage bit set:]
 *     Bytes n,n+1 : uint16 big-endian — UTF-8 byte length of the message
 *     Bytes n+2+  : UTF-8 message text (exactly the declared byte count, NOT null-terminated)
 *
 * On receiving a notification this handler:
 *   1. Persists block/unblock state and sends a local broadcast.
 *   2. Sends ACTION_SHOW_OPERATOR_MESSAGE so MainActivity shows the text immediately.
 *   3. Calls TmsMqttManager.triggerHouseKeeping() directly when any download/update
 *      flag is set.  The work runs in TmsMqttManager's coroutine scope — independent
 *      of MainActivity's lifecycle — so downloads proceed even when the launcher is
 *      in the background.
 */
class TmsNotificationHandler(private val context: Context) {

    private val credentialStore = TmsCredentialStore(context)

    /**
     * Entry point called by TmsMqttManager for every received message.
     *
     * @param topic   The MQTT topic the message arrived on.
     * @param payload Raw bytes from the MQTT message payload.
     */
    fun handleMessage(topic: String, payload: ByteArray) {
        Log.d(TAG, "Message on topic '$topic', ${payload.size} byte(s)")

        if (payload.isEmpty()) {
            Log.w(TAG, "Empty payload on $topic — ignoring")
            return
        }

        val notification = try {
            parseNotification(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse notification packet: ${e.message}", e)
            return
        }

        Log.i(TAG, "Parsed: param=${notification.paramDownloadPending}, " +
            "app=${notification.appDownloadPending}, fw=${notification.firmwareUpdatePending}, " +
            "launcherCfg=${notification.launcherConfigDownloadPending}, " +
            "urgent=${notification.urgent}, block=${notification.blockAction}, " +
            "msg=${notification.message}")

        dispatch(notification)
    }

    // ── Packet parser ─────────────────────────────────────────────────────────

    private fun parseNotification(payload: ByteArray): TmsNotification {
        val flags = payload[0].toInt() and 0xFF  // treat as unsigned byte
        var pos   = 1                             // read cursor, advances past each optional field

        val blockAction = when (flags and BLOCK_ACTION_MASK) {
            BLOCK_ACTION_UNBLOCK -> BlockAction.UNBLOCK
            BLOCK_ACTION_BLOCK   -> BlockAction.BLOCK
            BLOCK_ACTION_REBOOT  -> BlockAction.REBOOT
            else                 -> BlockAction.NONE
        }

        // Extended flags byte — always present in v2 packets; default to 0 for legacy 1-byte packets.
        val extFlags = if (payload.size > pos) {
            val ext = payload[pos].toInt() and 0xFF
            pos++
            ext
        } else 0

        // Optional: 10 fixed-length ASCII bytes for the offline unlock code.
        val unlockCode: String? = if ((flags and FLAG_HAS_UNLOCK_CODE) != 0) {
            require(payload.size >= pos + 10) {
                "HasUnlockCode set but packet too short for 10-byte code (${payload.size} bytes)"
            }
            val code = String(payload, pos, 10, Charsets.US_ASCII)
            pos += 10
            code
        } else null

        // Optional: uint16 length-prefixed UTF-8 message.
        val message: String? = if ((flags and FLAG_HAS_MESSAGE) != 0) {
            require(payload.size >= pos + 2) {
                "HasMessage set but packet too short for length field at pos $pos"
            }
            val msgLen = ((payload[pos].toInt() and 0xFF) shl 8) or
                         ((payload[pos + 1].toInt() and 0xFF))
            pos += 2
            require(payload.size >= pos + msgLen) {
                "Packet declares $msgLen message bytes but only ${payload.size - pos} available"
            }
            String(payload, pos, msgLen, Charsets.UTF_8)
        } else null

        return TmsNotification(
            paramDownloadPending          = (flags and FLAG_PARAM_DOWNLOAD)          != 0,
            appDownloadPending            = (flags and FLAG_APP_DOWNLOAD)            != 0,
            firmwareUpdatePending         = (flags and FLAG_FW_UPDATE)               != 0,
            urgent                        = (flags and FLAG_URGENT)                  != 0,
            blockAction                   = blockAction,
            unlockCode                    = unlockCode,
            message                       = message,
            launcherConfigDownloadPending = (extFlags and EXT_FLAG_LAUNCHER_CONFIG_DL) != 0
        )
    }

    // ── Dispatcher ────────────────────────────────────────────────────────────

    private fun dispatch(notification: TmsNotification) {

        // 1. Block state — takes priority. Persisted to survive reboots.
        when (notification.blockAction) {
            BlockAction.BLOCK -> {
                // If the operator performed an offline self-unlock, ignore this block
                // command — it is the server's stale persistent block being re-delivered
                // on reconnect, sent before the server has had a chance to process our
                // blk=0 status report. TmsMqttManager already published blk=0 immediately
                // on connect; once the server processes it, it will stop re-sending the block.
                if (credentialStore.isSelfUnlockPending()) {
                    Log.w(TAG, "BLOCK command received but self-unlock is pending — ignoring stale block")
                    return
                }
                Log.w(TAG, "Terminal BLOCK requested by TMS (unlockCode=${notification.unlockCode != null})")
                // Persist all block data before starting the activity so it survives
                // process death and is available on the next cold start.
                credentialStore.saveBlockState(true)
                val blockMsg = notification.message ?: context.getString(R.string.block_default_message)
                credentialStore.saveBlockMessage(blockMsg)
                if (notification.unlockCode != null) {
                    credentialStore.saveUnlockCode(notification.unlockCode)
                } else {
                    credentialStore.clearUnlockCode()
                }
                context.sendBroadcast(
                    Intent(ACTION_BLOCK_TERMINAL).apply { `package` = context.packageName }
                )
                // BlockedActivity reads message and unlock code from TmsCredentialStore.
                // FLAG_ACTIVITY_NEW_TASK is required when starting from a non-Activity context.
                context.startActivity(
                    Intent(context, BlockedActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                )
            }
            BlockAction.UNBLOCK -> {
                Log.i(TAG, "Terminal UNBLOCK requested by TMS")
                credentialStore.saveBlockState(false)
                credentialStore.clearBlockMessage()
                credentialStore.clearUnlockCode()
                context.sendBroadcast(
                    Intent(ACTION_UNBLOCK_TERMINAL).apply { `package` = context.packageName }
                )
                // Finish BlockedActivity — MainActivity underneath becomes visible again.
                BlockedActivity.instance?.finish()
            }
            BlockAction.REBOOT -> {
                Log.w(TAG, "REBOOT requested by TMS — rebooting device")
                try {
                    APIProxy.getDeviceEngine(context).platform.rebootDevice()
                } catch (e: Exception) {
                    Log.e(TAG, "rebootDevice() failed: ${e.message}", e)
                }
                return  // nothing further to dispatch after a reboot
            }
            BlockAction.NONE -> { /* no state change */ }
        }

        // 2. Operator message — broadcast directly so MainActivity shows it immediately.
        // Block packets may carry a message too, but that message is shown in BlockedActivity
        // as the block reason; it is not a standalone operator message.
        if (notification.blockAction == BlockAction.NONE) {
            notification.message?.let { msg ->
                Log.i(TAG, "Operator message: $msg")
                context.sendBroadcast(
                    Intent(ACTION_SHOW_OPERATOR_MESSAGE).apply {
                        `package` = context.packageName
                        putExtra(EXTRA_MESSAGE_TEXT, msg)
                    }
                )
            }
        }

        // 3a. Parameter download — handled independently via MQTT paramreq/paramres + HTTP.
        //     Does NOT go through HouseKeeping; ParamManager manages the full pipeline.
        if (notification.paramDownloadPending) {
            Log.i(TAG, "Param download pending — initiating paramreq via ParamManager")
            ParamManager.requestParamDownload(context)
        }

        // 3b. App / firmware download — triggers full MQTT HouseKeeping (hkreq/hkresp).
        //     On fresh install LauncherConfig must be applied first; defer if not yet ready.
        val needsHouseKeeping = notification.appDownloadPending || notification.firmwareUpdatePending
        if (needsHouseKeeping) {
            if (LauncherConfigManager.isConfigApplied(context)) {
                Log.i(TAG, "HouseKeeping triggered (app=${notification.appDownloadPending}, " +
                    "fw=${notification.firmwareUpdatePending}, urgent=${notification.urgent})")
                one.globalconnect.xtmsagent.mqtt.TmsMqttManager.triggerHouseKeeping(notification.urgent)
            } else {
                Log.i(TAG, "HouseKeeping deferred — LauncherConfig not yet applied " +
                    "(app=${notification.appDownloadPending}, fw=${notification.firmwareUpdatePending})")
                one.globalconnect.xtmsagent.mqtt.TmsMqttManager.setPendingHouseKeeping(notification.urgent)
            }
        }

        // 4. LauncherConfig download — independent of HouseKeeping; runs in TmsMqttManager scope.
        if (notification.launcherConfigDownloadPending) {
            Log.i(TAG, "Triggering LauncherConfig download (urgent=${notification.urgent})")
            one.globalconnect.xtmsagent.mqtt.TmsMqttManager.triggerLauncherConfigDownload(notification.urgent)
        }
    }
}