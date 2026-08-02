package one.globalconnect.pinpad.security

import android.content.Context
import android.os.SystemClock
import java.security.MessageDigest

class PinpadSecurityConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun protectSecretKeyInjection(): Boolean =
        prefs.getBoolean(KEY_PROTECT_SECRET_KEY_INJECTION, false)

    fun setProtectSecretKeyInjection(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PROTECT_SECRET_KEY_INJECTION, enabled).apply()
    }

    fun verifyKeyLoadPasswords(password1: String, password2: String): VerificationResult {
        val remaining = cooldownRemainingMs()
        if (remaining > 0) return VerificationResult.Cooldown(remaining)

        val valid = secureEquals(hash(password1), keyLoadPassword1Hash()) &&
            secureEquals(hash(password2), keyLoadPassword2Hash())
        if (valid) {
            prefs.edit().putInt(KEY_KEYLOAD_FAILED_ATTEMPTS, 0).remove(KEY_KEYLOAD_COOLDOWN_END).apply()
            return VerificationResult.Success
        }

        val attempts = prefs.getInt(KEY_KEYLOAD_FAILED_ATTEMPTS, 0) + 1
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            prefs.edit()
                .putInt(KEY_KEYLOAD_FAILED_ATTEMPTS, 0)
                .putLong(KEY_KEYLOAD_COOLDOWN_END, SystemClock.elapsedRealtime() + COOLDOWN_MS)
                .apply()
            return VerificationResult.Cooldown(COOLDOWN_MS)
        }
        prefs.edit().putInt(KEY_KEYLOAD_FAILED_ATTEMPTS, attempts).apply()
        return VerificationResult.Rejected(MAX_FAILED_ATTEMPTS - attempts)
    }

    fun updateKeyLoadPasswords(password1: String, password2: String): Boolean {
        if (!password1.isSevenDigitPassword() || !password2.isSevenDigitPassword() || password1 == password2) {
            return false
        }
        prefs.edit()
            .putString(KEY_KEYLOAD_PASSWORD_1_HASH, hash(password1))
            .putString(KEY_KEYLOAD_PASSWORD_2_HASH, hash(password2))
            .putInt(KEY_KEYLOAD_FAILED_ATTEMPTS, 0)
            .remove(KEY_KEYLOAD_COOLDOWN_END)
            .apply()
        return true
    }

    fun updateSettingsPasswords(
        exitPassword1: String?,
        exitPassword2: String?,
        androidConfigPassword1: String?,
        androidConfigPassword2: String?,
    ): Boolean {
        val updates = listOf(
            KEY_EXIT_PASSWORD_1_HASH to exitPassword1,
            KEY_EXIT_PASSWORD_2_HASH to exitPassword2,
            KEY_ANDROID_CONFIG_PASSWORD_1_HASH to androidConfigPassword1,
            KEY_ANDROID_CONFIG_PASSWORD_2_HASH to androidConfigPassword2,
        )
        if (updates.any { (_, value) -> value != null && !value.isValidSettingsPassword() }) return false

        val editor = prefs.edit()
        updates.forEach { (key, value) ->
            if (value != null) editor.putString(key, hash(value))
        }
        editor.apply()
        return true
    }

    fun verifySettingsPasswords(scope: SettingsPasswordScope, password1: String, password2: String): Boolean {
        val (key1, key2) = when (scope) {
            SettingsPasswordScope.ExitHome -> KEY_EXIT_PASSWORD_1_HASH to KEY_EXIT_PASSWORD_2_HASH
            SettingsPasswordScope.AndroidConfig ->
                KEY_ANDROID_CONFIG_PASSWORD_1_HASH to KEY_ANDROID_CONFIG_PASSWORD_2_HASH
        }
        val default1 = DEFAULT_SETTINGS_PASSWORD_1_HASH
        val default2 = DEFAULT_SETTINGS_PASSWORD_2_HASH
        return secureEquals(hash(password1), prefs.getString(key1, default1).orEmpty()) &&
            secureEquals(hash(password2), prefs.getString(key2, default2).orEmpty())
    }

    private fun keyLoadPassword1Hash(): String =
        prefs.getString(KEY_KEYLOAD_PASSWORD_1_HASH, DEFAULT_KEYLOAD_PASSWORD_1_HASH)
            ?: DEFAULT_KEYLOAD_PASSWORD_1_HASH

    private fun keyLoadPassword2Hash(): String =
        prefs.getString(KEY_KEYLOAD_PASSWORD_2_HASH, DEFAULT_KEYLOAD_PASSWORD_2_HASH)
            ?: DEFAULT_KEYLOAD_PASSWORD_2_HASH

    private fun cooldownRemainingMs(): Long =
        (prefs.getLong(KEY_KEYLOAD_COOLDOWN_END, 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    private fun secureEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.US_ASCII), right.toByteArray(Charsets.US_ASCII))

    private fun String.isSevenDigitPassword(): Boolean = length == 7 && all(Char::isDigit)

    private fun String.isValidSettingsPassword(): Boolean = length in 4..12 && all(Char::isDigit)

    sealed interface VerificationResult {
        data object Success : VerificationResult
        data class Rejected(val attemptsRemaining: Int) : VerificationResult
        data class Cooldown(val remainingMs: Long) : VerificationResult
    }

    enum class SettingsPasswordScope {
        ExitHome,
        AndroidConfig,
    }

    private companion object {
        private const val PREFS_NAME = "pinpad_security_config"
        private const val KEY_PROTECT_SECRET_KEY_INJECTION = "protect_secret_key_injection"
        private const val KEY_KEYLOAD_PASSWORD_1_HASH = "keyload_password_1_hash"
        private const val KEY_KEYLOAD_PASSWORD_2_HASH = "keyload_password_2_hash"
        private const val KEY_KEYLOAD_FAILED_ATTEMPTS = "keyload_failed_attempts"
        private const val KEY_KEYLOAD_COOLDOWN_END = "keyload_cooldown_end"
        private const val KEY_EXIT_PASSWORD_1_HASH = "exit_password_1_hash"
        private const val KEY_EXIT_PASSWORD_2_HASH = "exit_password_2_hash"
        private const val KEY_ANDROID_CONFIG_PASSWORD_1_HASH = "android_config_password_1_hash"
        private const val KEY_ANDROID_CONFIG_PASSWORD_2_HASH = "android_config_password_2_hash"
        private const val MAX_FAILED_ATTEMPTS = 3
        private const val COOLDOWN_MS = 60_000L

        private const val DEFAULT_KEYLOAD_PASSWORD_1_HASH =
            "8BB0CF6EB9B17D0F7D22B456F121257DC1254E1F01665370476383EA776DF414"
        private const val DEFAULT_KEYLOAD_PASSWORD_2_HASH =
            "AB58EA9926A3FBC39333916C72867EBB140088BF148FEED71D13C24A03C65BE2"
        private const val DEFAULT_SETTINGS_PASSWORD_1_HASH =
            "C04D115972EADBAF9F396674DDDB2BC38ABB63A0BBA07C2823837B7A68BCD992"
        private const val DEFAULT_SETTINGS_PASSWORD_2_HASH =
            "BF2CA90A2CDAF8A85FFB61412B672F4D1308A07D42788EED8B61FEC0DC54F188"
    }
}
