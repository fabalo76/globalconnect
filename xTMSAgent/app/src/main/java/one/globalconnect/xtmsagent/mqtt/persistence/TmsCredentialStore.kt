@file:Suppress("DEPRECATION")
package one.globalconnect.xtmsagent.mqtt.persistence

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.Locale

private const val TAG = "TmsCredentialStore"
private const val PREFS_FILE = "tms_credentials"

private const val KEY_TERM_ID = "term_id"
private const val KEY_BROKER_HOST = "broker_host"
private const val KEY_IS_BLOCKED = "is_blocked"
private const val KEY_BLOCK_MESSAGE = "block_message"
private const val KEY_UNLOCK_CODE = "unlock_code"
private const val KEY_SELF_UNLOCK_PENDING = "self_unlock_pending"

private const val LEGACY_KEY_PASSWORD = "mqtt_password"
private const val LEGACY_KEY_CERT_DER = "server_cert_der"

class TmsCredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences unavailable; falling back to plain prefs: ${e.message}")
            context.getSharedPreferences("${PREFS_FILE}_plain", Context.MODE_PRIVATE)
        }
    }

    fun saveTermId(termId: String) {
        prefs.edit().putString(KEY_TERM_ID, termId).apply()
        Log.d(TAG, "TermID saved: $termId")
    }

    fun loadTermId(): String? = prefs.getString(KEY_TERM_ID, null)

    fun saveBrokerHost(host: String) {
        prefs.edit().putString(KEY_BROKER_HOST, host).apply()
        Log.d(TAG, "Broker host saved: $host")
    }

    fun loadBrokerHost(): String? = prefs.getString(KEY_BROKER_HOST, null)

    fun saveBlockState(blocked: Boolean) {
        prefs.edit().putBoolean(KEY_IS_BLOCKED, blocked).apply()
        Log.i(TAG, "Block state saved: blocked=$blocked")
    }

    fun isBlocked(): Boolean = prefs.getBoolean(KEY_IS_BLOCKED, false)

    fun saveBlockMessage(message: String) {
        prefs.edit().putString(KEY_BLOCK_MESSAGE, message).apply()
        Log.d(TAG, "Block message saved (${message.length} chars)")
    }

    fun loadBlockMessage(): String? = prefs.getString(KEY_BLOCK_MESSAGE, null)

    fun clearBlockMessage() {
        prefs.edit().remove(KEY_BLOCK_MESSAGE).apply()
    }

    fun saveUnlockCode(code: String) {
        val normalizedCode = code.trim()
        if (!normalizedCode.matches(Regex("\\d{10}"))) {
            prefs.edit().remove(KEY_UNLOCK_CODE).apply()
            Log.w(TAG, String.format(Locale.US, "Rejected offline unlock code length=%d", normalizedCode.length))
            return
        }
        prefs.edit().putString(KEY_UNLOCK_CODE, normalizedCode).apply()
        Log.d(TAG, "Offline unlock code saved")
    }

    fun loadUnlockCode(): String? = prefs.getString(KEY_UNLOCK_CODE, null)

    fun clearUnlockCode() {
        prefs.edit().remove(KEY_UNLOCK_CODE).apply()
    }

    fun saveSelfUnlockPending(pending: Boolean) {
        prefs.edit().putBoolean(KEY_SELF_UNLOCK_PENDING, pending).apply()
        Log.d(TAG, "Self-unlock pending: $pending")
    }

    fun isSelfUnlockPending(): Boolean = prefs.getBoolean(KEY_SELF_UNLOCK_PENDING, false)

    fun clearLegacyMqttCredentials() {
        prefs.edit()
            .remove(LEGACY_KEY_PASSWORD)
            .remove(LEGACY_KEY_CERT_DER)
            .apply()
        Log.i(TAG, "Cleared legacy MQTT password credentials")
    }

    fun commitSelfUnlock() {
        prefs.edit()
            .putBoolean(KEY_IS_BLOCKED, false)
            .remove(KEY_BLOCK_MESSAGE)
            .remove(KEY_UNLOCK_CODE)
            .putBoolean(KEY_SELF_UNLOCK_PENDING, true)
            .commit()
        Log.i(TAG, "Self-unlock committed synchronously (blocked=false, selfUnlockPending=true)")
    }
}
