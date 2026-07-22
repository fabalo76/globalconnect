package one.globalconnect.paymentapp.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.globalconnect.paymentapp.printer.NexGoPaymentPrinter
import org.json.JSONObject

private const val TAG = "AdminMessageReceiver"

class AdminMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AdminRequestConstants.ACTION_ADMIN_MESSAGE) return

        val payloadText = intent.getStringExtra(AdminRequestConstants.EXTRA_ADMIN_MESSAGE_JSON)
        if (payloadText.isNullOrBlank()) {
            Log.w(TAG, "Admin message ignored: empty payload")
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val payload = JSONObject(payloadText)
                val params = payload.optJSONObject("params") ?: payload
                val ticket = params.toAdminTicketData()
                Log.i(
                    TAG,
                    "Admin message received type=${payload.optString("type", "(none)")} " +
                        "ticketId=${ticket.ticketId.ifBlank { "(none)" }} printTicket=${params.optBoolean("printTicket", false)}"
                )

                if (ticket.messageText.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, ticket.messageText, Toast.LENGTH_LONG).show()
                    }
                }

                if (params.optBoolean("printTicket", false)) {
                    NexGoPaymentPrinter.printAdminTicket(appContext, ticket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle admin message: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun JSONObject.toAdminTicketData(): AdminTicketData =
        AdminTicketData(
            messageText = optStringAny("messageText", "MessageText", "message", "Message"),
            ticketId = optStringAny("ticketId", "TicketId"),
            ticketTitle = optStringAny("ticketTitle", "TicketTitle", "title", "Title"),
            requestType = optStringAny("requestType", "RequestType"),
            deviceSerial = optStringAny("deviceSerial", "DeviceSerial", "serialNumber", "SerialNumber"),
            terminalId = optStringAny("terminalId", "TerminalId"),
            laneId = optStringAny("laneId", "LaneId"),
            createdAt = optStringAny("createdAt", "CreatedAt"),
        )

    private fun JSONObject.optStringAny(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key, "")
            if (value.isNotBlank()) return value
        }
        return ""
    }
}
