package one.globalconnect.keyinjection

import android.content.Context
import android.content.SharedPreferences
import one.globalconnect.keyinjection.util.Logger
import java.security.MessageDigest

/**
 * Handles storage and verification of the two application passwords. It
 * persists hashed values in shared preferences and applies a timeout after a
 * configurable number of failed attempts.
 *
 * Author: Fabian Ramirez
 */
class PasswordManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
    private val TAG = "PasswordManager"

    companion object {
        private const val KEY_PASS1 = "pass1"
        private const val KEY_PASS2 = "pass2"
        private const val KEY_ATTEMPTS = "attempts"
        private const val KEY_COOLDOWN_END = "cooldown_end"
        private const val KEY_PASS1_CHANGED = "pass1_changed"
        private const val MAX_ATTEMPTS = 3
        private const val COOLDOWN_MS = 60_000L
    }

    init {
        if (!prefs.contains(KEY_PASS1)) {
            prefs.edit()
                .putString(KEY_PASS1, BuildConfig.DEFAULT_PASS1_HASH)
                .putString(KEY_PASS2, BuildConfig.DEFAULT_PASS2_HASH)
                .putBoolean(KEY_PASS1_CHANGED, false)
                .apply()
        }
    }

    /**
     * Hashes a string using SHA-256.
     *
     * @param input the string to hash
     * @return the hashed string
     */
    private fun hash(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns the remaining cooldown time in milliseconds after too many
     * failed attempts. A value of zero indicates that the user may retry.
     *
     * @return milliseconds left before another attempt is allowed
     */
    fun isInCooldown(): Long {
        val end = prefs.getLong(KEY_COOLDOWN_END, 0L)
        val now = System.currentTimeMillis()
        val remaining = if (now < end) end - now else 0L
        if (remaining > 0) {
            Logger.w(TAG, "Cooldown active: $remaining ms remaining")
        }
        return remaining
    }

    /**
     * Updates the attempt counter and starts a cooldown when the maximum
     * number of failed attempts is exceeded.
     */
    private fun recordFailedAttempt() {
        val attempts = prefs.getInt(KEY_ATTEMPTS, 0) + 1
        Logger.w(TAG, "Failed password attempt $attempts")
        if (attempts >= MAX_ATTEMPTS) {
            val end = System.currentTimeMillis() + COOLDOWN_MS
            Logger.w(TAG, "Max attempts reached; entering cooldown for ${COOLDOWN_MS}ms")
            prefs.edit()
                .putInt(KEY_ATTEMPTS, 0)
                .putLong(KEY_COOLDOWN_END, end)
                .apply()
        } else {
            prefs.edit().putInt(KEY_ATTEMPTS, attempts).apply()
        }
    }

    /**
     * Resets the stored failed-attempt counter.
     */
    fun resetAttempts() {
        Logger.d(TAG, "Resetting failed attempt counter")
        prefs.edit().putInt(KEY_ATTEMPTS, 0).apply()
    }

    /**
     * Checks the first password. On success, the failed-attempt counter is reset;
     * otherwise the counter is incremented and a cooldown may start.
     *
     * @param input user-entered password to verify
     * @return `true` if the password matches the stored hash
     */
    fun verifyPassword1(input: String): Boolean {
        Logger.i(TAG, "Password 1 verification attempt")
        val ok = prefs.getString(KEY_PASS1, "") == hash(input)
        if (ok) {
            Logger.i(TAG, "Password 1 verified")
            resetAttempts()
        } else {
            Logger.w(TAG, "Password 1 failed")
            recordFailedAttempt()
        }
        return ok
    }

    /**
     * Checks the second password and updates the failed-attempt state.
     *
     * @param input user-entered password to verify
     * @return `true` if the password matches the stored hash
     */
    fun verifyPassword2(input: String): Boolean {
        Logger.i(TAG, "Password 2 verification attempt")
        val ok = prefs.getString(KEY_PASS2, "") == hash(input)
        if (ok) {
            Logger.i(TAG, "Password 2 verified")
            resetAttempts()
        } else {
            Logger.w(TAG, "Password 2 failed")
            recordFailedAttempt()
        }
        return ok
    }

    /**
     * Indicates whether the first password has ever been changed.
     *
     * @return `true` if the first password has been modified from its default
     */
    fun isPassword1Changed(): Boolean = prefs.getBoolean(KEY_PASS1_CHANGED, false)

    /**
     * Determines if the second password still matches the factory default.
     *
     * @return `true` if the second password remains the factory default
     */
    fun isPassword2Default(): Boolean =
        prefs.getString(KEY_PASS2, "") == BuildConfig.DEFAULT_PASS2_HASH

    /**
     * Attempts to update the first password.
     *
     * @param newPass candidate password; must be seven digits and different from current passwords
     * @return `true` if the password was changed successfully
     */
    fun updatePassword1(newPass: String): Boolean {
        Logger.i(TAG, "Attempting to change password 1")
        if (newPass.length != 7 || !newPass.all { it.isDigit() }) {
            Logger.w(TAG, "Password 1 change failed: invalid format")
            return false
        }
        val hashed = hash(newPass)
        if (hashed == prefs.getString(KEY_PASS1, "")) {
            Logger.w(TAG, "Password 1 change failed: same as current")
            return false
        }
        if (hashed == prefs.getString(KEY_PASS2, "")) {
            Logger.w(TAG, "Password 1 change failed: matches password 2")
            return false
        }
        prefs.edit()
            .putString(KEY_PASS1, hashed)
            .putBoolean(KEY_PASS1_CHANGED, true)
            .apply()
        Logger.i(TAG, "Password 1 successfully changed")
        return true
    }

    /**
     * Attempts to update the second password.
     *
     * @param newPass candidate password; must be seven digits and different from current passwords
     * @return `true` if the password was changed successfully
     */
    fun updatePassword2(newPass: String): Boolean {
        Logger.i(TAG, "Attempting to change password 2")
        if (newPass.length != 7 || !newPass.all { it.isDigit() }) {
            Logger.w(TAG, "Password 2 change failed: invalid format")
            return false
        }
        val hashed = hash(newPass)
        if (hashed == prefs.getString(KEY_PASS2, "")) {
            Logger.w(TAG, "Password 2 change failed: same as current")
            return false
        }
        if (hashed == prefs.getString(KEY_PASS1, "")) {
            Logger.w(TAG, "Password 2 change failed: matches password 1")
            return false
        }
        prefs.edit().putString(KEY_PASS2, hashed).apply()
        Logger.i(TAG, "Password 2 successfully changed")
        return true
    }
}
