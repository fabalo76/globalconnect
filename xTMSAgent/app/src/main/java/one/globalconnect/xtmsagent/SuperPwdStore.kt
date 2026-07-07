@file:Suppress("DEPRECATION")
package one.globalconnect.xtmsagent

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import one.globalconnect.xtmsagent.BuildConfig

/**
 * Secure storage for the two super-user password seeds.
 */
object SuperPwdStore {

    private const val PREFS_NAME = "xtmsagent_super_pwd"
    private const val KEY_SEED_PREFIX = "seed_"
    private val DEFAULT_SEEDS = arrayOf(BuildConfig.DEFAULT_SEED_0, BuildConfig.DEFAULT_SEED_1)

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun init(context: Context) {
        val p = prefs(context)
        if (!p.contains("${KEY_SEED_PREFIX}0")) {
            p.edit()
                .putString("${KEY_SEED_PREFIX}0", DEFAULT_SEEDS[0])
                .putString("${KEY_SEED_PREFIX}1", DEFAULT_SEEDS[1])
                .apply()
        }
    }

    fun loadSeed(context: Context, index: Int): String =
        prefs(context).getString("${KEY_SEED_PREFIX}$index", DEFAULT_SEEDS[index])
            ?: DEFAULT_SEEDS[index]

    fun saveSeed(context: Context, index: Int, seed: String) {
        prefs(context).edit().putString("${KEY_SEED_PREFIX}$index", seed).apply()
    }
}
