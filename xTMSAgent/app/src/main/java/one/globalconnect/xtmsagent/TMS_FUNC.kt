package one.globalconnect.xtmsagent

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import one.globalconnect.xtmsagent.MainActivity.Companion.Logd
import one.globalconnect.xtmsagent.MainActivity.Companion.vg_sIntrenalPath
import java.io.File

object TMSFunc {
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
        if(cfgLastModify == fCfg.lastModified())
            return false
        cfgLastModify = fCfg.lastModified()
        Logd(Exception("fCfg=${fCfg.absolutePath}"))
        val data = Gson().fromJson(fCfg.readText(),cfg::class.java)
        if(data.theme.foreground_color[0] != '#')
            data.theme.foreground_color = "#${data.theme.foreground_color}"
        if(data.theme.background_color[0] != '#')
            data.theme.background_color = "#${data.theme.background_color}"
        if(data.theme.font_color[0] != '#')
            data.theme.font_color = "#${data.theme.font_color}"
        if(data.theme.status_bar_color[0] != '#')
            data.theme.status_bar_color = "#${data.theme.status_bar_color}"
        if(data.theme.navigation_bar_color[0] != '#')
            data.theme.navigation_bar_color = "#${data.theme.navigation_bar_color}"
        MainActivity.stTheme = data.theme
        mqttCfg = data.mqtt
        tmsCfg = data.tms
        tmsCfg.sn = MainActivity.vg_sSN
        return true
    }
}
