package one.globalconnect.pinpad.storage

import android.content.Context
import java.security.MessageDigest

class PinpadSettingsPasswordStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun verify(password1: String, password2: String): Boolean {
        ensureDefaultPasswords()
        val storedPassword1 = prefs.getString(KEY_PASSWORD_1_HASH, null) ?: DEFAULT_PASSWORD_1_HASH
        val storedPassword2 = prefs.getString(KEY_PASSWORD_2_HASH, null) ?: DEFAULT_PASSWORD_2_HASH
        return sha256(password1) == storedPassword1 && sha256(password2) == storedPassword2
    }

    private fun ensureDefaultPasswords() {
        val storedPassword1 = prefs.getString(KEY_PASSWORD_1_HASH, null)
        val storedPassword2 = prefs.getString(KEY_PASSWORD_2_HASH, null)
        val hasCurrentDefaults =
            storedPassword1 == DEFAULT_PASSWORD_1_HASH && storedPassword2 == DEFAULT_PASSWORD_2_HASH
        if (hasCurrentDefaults) return

        val hasLegacyDefaults =
            storedPassword1 == LEGACY_DEFAULT_PASSWORD_1_HASH &&
                storedPassword2 == LEGACY_DEFAULT_PASSWORD_2_HASH
        val hasCustomPasswords = storedPassword1 != null && storedPassword2 != null && !hasLegacyDefaults
        if (hasCustomPasswords) return

        prefs.edit()
            .putString(KEY_PASSWORD_1_HASH, DEFAULT_PASSWORD_1_HASH)
            .putString(KEY_PASSWORD_2_HASH, DEFAULT_PASSWORD_2_HASH)
            .apply()
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02X".format(it) }
    }

    private companion object {
        private const val PREFS_NAME = "pinpad_settings_passwords"
        private const val KEY_PASSWORD_1_HASH = "password_1_hash"
        private const val KEY_PASSWORD_2_HASH = "password_2_hash"
        private const val DEFAULT_PASSWORD_1_HASH =
            "C5EDC4E6D5173223A29CE46B7AECFC1B81EFA9338A3372238D6D3601420B84B0"
        private const val DEFAULT_PASSWORD_2_HASH =
            "BF2CA90A2CDAF8A85FFB61412B672F4D1308A07D42788EED8B61FEC0DC54F188"
        private const val LEGACY_DEFAULT_PASSWORD_1_HASH =
            "0B067AD0654363FE6495780629B1E21B31E6419E0197DAB245532BCABB7AD06C"
        private const val LEGACY_DEFAULT_PASSWORD_2_HASH =
            "9C6010E500448BB6AE2877CEAB21B9014DF326006DE52177CF051562306C9FB1"
    }
}
