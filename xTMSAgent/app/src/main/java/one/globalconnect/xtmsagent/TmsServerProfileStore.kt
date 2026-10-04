@file:Suppress("DEPRECATION")
package one.globalconnect.xtmsagent

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson

/** A separate encrypted snapshot lets operators return to the original AWS instance. */
class TmsServerProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs by lazy { EncryptedSharedPreferences.create(
        appContext,
        "tms_server_profiles",
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    ) }
    private val gson = Gson()

    fun save(config: TMSFunc.tms) {
        val id = TmsServerProfile.current(config).id
        check(prefs.edit().putString(id, gson.toJson(config))
            .putInt("${id}_mqtt_port", TMSFunc.mqttCfg.mqtt_port).commit())
    }

    fun select(config: TMSFunc.tms) {
        val id = TmsServerProfile.current(config).id
        check(prefs.edit().putString(id, gson.toJson(config)).putString("active", id).commit())
    }

    fun mqttPort(profile: TmsServerProfile): Int =
        if (profile == TmsServerProfile.DEMO) 443 else prefs.getInt("${profile.id}_mqtt_port", 8883)

    fun active(): TMSFunc.tms? {
        // Existing AWS installations do not need a new vault until the selector is used.
        if (!java.io.File(appContext.applicationInfo.dataDir, "shared_prefs/tms_server_profiles.xml").isFile) return null
        return prefs.getString("active", null)?.let { id ->
            TmsServerProfile.entries.firstOrNull { it.id == id }?.let(::load)
        }
    }

    fun load(profile: TmsServerProfile): TMSFunc.tms =
        prefs.getString(profile.id, null)?.let { gson.fromJson(it, TMSFunc.tms::class.java) }
            ?: profile.defaults().also {
                if (profile == TmsServerProfile.DEMO) {
                    it.download_credential_id = BuildConfig.DEMO_DOWNLOAD_CREDENTIAL_ID
                    it.download_secret = BuildConfig.DEMO_DOWNLOAD_CREDENTIAL_SECRET
                }
            }
}
