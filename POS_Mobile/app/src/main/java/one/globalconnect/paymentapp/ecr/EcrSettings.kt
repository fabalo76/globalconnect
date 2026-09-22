package one.globalconnect.paymentapp.ecr

import android.content.Context

data class EcrSettings(val enabled: Boolean = false, val transport: String = "TCP/IP",
    val port: Int = 9100, val kiosk: Boolean = false) {
    companion object {
        fun read(context: Context): EcrSettings {
            val p = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
            return EcrSettings(p.getBoolean("ecr_enabled",false), p.getString("ecr_transport","TCP/IP")!!,
                p.getInt("ecr_port",9100),p.getBoolean("ecr_kiosk",false))
        }
    }
    fun save(context: Context) = synchronized(EcrRuntime) {
        check(!EcrRuntime.busy) { "ECR operation in progress" }
        require(port in 1024..65535)
        require(!enabled || transport == "TCP/IP")
        check(context.getSharedPreferences("app_preferences",Context.MODE_PRIVATE).edit()
            .putBoolean("ecr_enabled",enabled).putString("ecr_transport",transport)
            .putInt("ecr_port",port).putBoolean("ecr_kiosk",kiosk).commit())
        EcrRuntime.configure(context, this)
    }
}
