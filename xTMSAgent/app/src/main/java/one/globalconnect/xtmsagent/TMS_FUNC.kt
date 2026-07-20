package one.globalconnect.xtmsagent

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import android.util.Log
import one.globalconnect.xtmsagent.MainActivity.Companion.Logd
import one.globalconnect.xtmsagent.MainActivity.Companion.vg_sIntrenalPath
import java.io.File

object TMSFunc {
    private const val TAG = "TMSFunc"
    private const val MAX_CONFIG_BYTES = 1024 * 1024
    data class theme(
        var background_color:String = "#FFF0F0F0",
        var foreground_color:String = "#FFF08784",
        var font_color:String =  "#FFFFFFFF",
        var font_size:Int = 12,
        var system_pwd_protection:Boolean = true,
        var enable_navigation_bar:Boolean = true,
        var enable_control_bar:Boolean = true,
        var status_bar_color:String = "#FF000000",
        var navigation_bar_color:String = "#FF000000",
    )
    data class tms(
        var sn:String = "",
        var server_addr:String = "api.dev.globalconnect.one",
        var api_host:String = "api.dev.globalconnect.one",
        var api_host_fallback:String = "yc1aix2k96.execute-api.us-east-1.amazonaws.com",
        var mqtt_host:String = "atypsm4jmdcoa-ats.iot.us-east-1.amazonaws.com",
        var tcp_port:Int = 5050,
        var tcp_ssl:Boolean = false,
        var ftp_port:Int = 990,
        var ftp_ssl:Boolean = false,
        var ftp_user:String = "user",
        var ftp_password:String = "",
        var nii:Int = 6789,
        var conn_timeout:Int = 10,
        var resp_timeout:Int = 60,
        var attempt_counter:Int = 2,
        // HTTP(S) file download (replaces FTPS) — provisioned via Launcher_Config.JSON
        var web_port_ssl:Boolean = true,
        var web_port:Int = 44388,
        var download_secret:String = "",
    ) {
        val webScheme: String get() = if (web_port_ssl) "https" else "http"
        val apiHost: String get() = api_host.ifBlank { server_addr }
        val mqttHost: String get() = mqtt_host.ifBlank { server_addr }
    }
    data class mqtt(
        var mqtt_port: Int = 8883,
        var keepalive: Int = 240,
        var probe_timeout: Int = 10_000,
        var status_interval: Int = 20,
    )
    data class cfg(
        @SerializedName("launcher_theme") var theme : theme = theme(),
        @SerializedName("tms_cfg") var tms : tms = tms(),
        @SerializedName("mqtt_cfg") var mqtt : mqtt = mqtt()
    )

    var mqttCfg: mqtt = mqtt()
    var tmsCfg: tms = tms()

    private var cfgLastModify:Long = 0

    fun ChkParamChange():Boolean {
        var fCfg = File(MainActivity.vg_sXtmsParam)
        if(false == fCfg.exists())
            fCfg = File("$vg_sIntrenalPath/cfg/Launcher_Config.JSON")
        if (!fCfg.isFile) {
            Log.w(TAG, "Launcher configuration file is unavailable; keeping current configuration")
            return false
        }
        if(cfgLastModify == fCfg.lastModified())
            return false
        Logd(Exception("fCfg=${fCfg.absolutePath}"))
        val data = try {
            if (fCfg.length() !in 1..MAX_CONFIG_BYTES.toLong()) {
                throw IllegalArgumentException("Launcher configuration size is invalid")
            }
            parseConfig(fCfg.readText(), MainActivity.vg_sSN)
                ?: throw IllegalArgumentException("Launcher configuration JSON is invalid")
        } catch (e: Exception) {
            Log.e(TAG, "Invalid launcher configuration; keeping current configuration", e)
            null
        } ?: return false

        cfgLastModify = fCfg.lastModified()
        MainActivity.stTheme = data.theme
        mqttCfg = data.mqtt
        tmsCfg = data.tms
        return true
    }

    internal fun parseConfig(raw: String, serialNumber: String): cfg? {
        val data = try {
            Gson().fromJson(raw, cfg::class.java)
        } catch (_: Exception) {
            null
        } ?: return null
        try {
        data.theme.foreground_color = normalizeColor(data.theme.foreground_color, "#FFF08784")
        data.theme.background_color = normalizeColor(data.theme.background_color, "#FFF0F0F0")
        data.theme.font_color = normalizeColor(data.theme.font_color, "#FFFFFFFF")
        data.theme.status_bar_color = normalizeColor(data.theme.status_bar_color, "#FF000000")
        data.theme.navigation_bar_color = normalizeColor(data.theme.navigation_bar_color, "#FF000000")
        data.theme.font_size = data.theme.font_size.coerceIn(8, 72)
        data.tms.server_addr = data.tms.server_addr.trim()
        data.tms.api_host = data.tms.api_host.trim()
        data.tms.api_host_fallback = data.tms.api_host_fallback.trim()
        data.tms.mqtt_host = data.tms.mqtt_host.trim()
        data.tms.tcp_port = normalizePort(data.tms.tcp_port, 5050)
        data.tms.web_port = normalizePort(data.tms.web_port, 443)
        data.tms.conn_timeout = data.tms.conn_timeout.coerceIn(1, 300)
        data.tms.resp_timeout = data.tms.resp_timeout.coerceIn(1, 600)
        data.tms.attempt_counter = data.tms.attempt_counter.coerceIn(1, 20)
        data.tms.sn = serialNumber.trim()
        data.mqtt.mqtt_port = normalizePort(data.mqtt.mqtt_port, 8883)
        data.mqtt.keepalive = data.mqtt.keepalive.coerceIn(30, 3600)
        data.mqtt.probe_timeout = data.mqtt.probe_timeout.coerceIn(1_000, 120_000)
        data.mqtt.status_interval = data.mqtt.status_interval.coerceIn(10, 86_400)
        return data
        } catch (_: Exception) {
            return null
        }
    }

    private fun normalizePort(value: Int, fallback: Int): Int = if (value in 1..65535) value else fallback

    private fun normalizeColor(value: String?, fallback: String): String {
        val candidate = value?.trim().orEmpty()
        val prefixed = if (candidate.startsWith('#')) candidate else "#$candidate"
        return if (prefixed.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) prefixed else fallback
    }
}
