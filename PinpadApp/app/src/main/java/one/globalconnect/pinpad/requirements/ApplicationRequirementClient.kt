package one.globalconnect.pinpad.requirements

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object ApplicationRequirementClient {
    fun requestTextToSpeech(context: Context, callback: (Boolean, String?) -> Unit) {
        request(context, CAPABILITY_ANDROID_TTS, callback)
    }

    fun request(
        context: Context,
        capability: String,
        callback: (Boolean, String?) -> Unit,
    ) {
        val appContext = context.applicationContext
        val serviceIntent = resolveAgentService(appContext)
        if (serviceIntent == null) {
            callback(false, "AGENT_APPLICATION_SERVICE_NOT_FOUND")
            return
        }
        val requestId = UUID.randomUUID().toString()
        val completed = AtomicBoolean(false)
        val responseHandler = Handler(Looper.getMainLooper())
        var connection: ServiceConnection? = null
        lateinit var timeout: Runnable
        fun complete(accepted: Boolean, error: String?) {
            if (!completed.compareAndSet(false, true)) return
            responseHandler.removeCallbacks(timeout)
            connection?.let { runCatching { appContext.unbindService(it) } }
            callback(accepted, error)
        }
        timeout = Runnable {
            complete(false, "AGENT_APPLICATION_SERVICE_TIMEOUT")
        }
        val responseMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what != MSG_APPLICATION_REQUIREMENT_RESPONSE) return
                val response = runCatching {
                    JSONObject(message.data.getString(KEY_RESPONSE_JSON).orEmpty())
                }.getOrNull()
                val status = response?.optString("status").orEmpty()
                val accepted = status == STATUS_INSTALL_REQUESTED || status == STATUS_ALREADY_REQUESTED
                complete(
                    accepted,
                    if (accepted) null
                    else response?.optString("errorCode")?.takeIf { it.isNotBlank() }
                        ?: "APPLICATION_REQUIREMENT_FAILED",
                )
            }
        })

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val message = Message.obtain(null, MSG_REQUEST_APPLICATION_REQUIREMENT).apply {
                    replyTo = responseMessenger
                    data = Bundle().apply {
                        putString(KEY_REQUEST_ID, requestId)
                        putString(KEY_PACKAGE_NAME, appContext.packageName)
                        putString(KEY_CAPABILITY, capability)
                    }
                }
                runCatching { Messenger(binder).send(message) }
                    .onFailure {
                        complete(false, "AGENT_APPLICATION_SERVICE_UNAVAILABLE")
                    }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                complete(false, "AGENT_APPLICATION_SERVICE_DISCONNECTED")
            }
        }
        if (!appContext.bindService(serviceIntent, connection!!, Context.BIND_AUTO_CREATE)) {
            complete(false, "AGENT_APPLICATION_SERVICE_UNAVAILABLE")
        } else {
            responseHandler.postDelayed(timeout, APPLICATION_REQUIREMENT_TIMEOUT_MS)
        }
    }

    private fun resolveAgentService(context: Context): Intent? {
        val implicit = Intent(ACTION_AGENT_APPLICATION_SERVICE)
        @Suppress("DEPRECATION")
        val service = context.packageManager.queryIntentServices(implicit, 0)
            .firstOrNull { it.serviceInfo.packageName.startsWith("one.globalconnect.xtmsagent") }
            ?.serviceInfo ?: return null
        return Intent(implicit).setComponent(ComponentName(service.packageName, service.name))
    }

    const val ACTION_APPLICATION_REQUIREMENT_READY =
        "one.globalconnect.xtmsagent.APPLICATION_REQUIREMENT_READY"
    const val EXTRA_CAPABILITY = "capability"
    const val CAPABILITY_ANDROID_TTS = "android.tts"
    const val CAPABILITY_ANDROID_TTS_SPANISH_LANGUAGE = "android.tts.language.es"
    const val CAPABILITY_ANDROID_TTS_MATEO_VOICE = "android.tts.voice.es.mateo"
    private const val ACTION_AGENT_APPLICATION_SERVICE =
        "one.globalconnect.xtmsagent.APPLICATION_LICENSE_SERVICE"
    private const val MSG_REQUEST_APPLICATION_REQUIREMENT = 5
    private const val MSG_APPLICATION_REQUIREMENT_RESPONSE = 6
    private const val KEY_REQUEST_ID = "requestId"
    private const val KEY_PACKAGE_NAME = "packageName"
    private const val KEY_CAPABILITY = "capability"
    private const val KEY_RESPONSE_JSON = "responseJson"
    private const val STATUS_INSTALL_REQUESTED = "install_requested"
    private const val STATUS_ALREADY_REQUESTED = "already_requested"
    private const val APPLICATION_REQUIREMENT_TIMEOUT_MS = 75_000L
}
