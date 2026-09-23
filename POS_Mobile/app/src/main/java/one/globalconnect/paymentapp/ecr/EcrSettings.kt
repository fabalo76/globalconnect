package one.globalconnect.paymentapp.ecr

import android.content.Context

data class EcrSettings(val enabled: Boolean = false, val transport: String = "TCP/IP",
    val port: Int = 9100, val kiosk: Boolean = false, val serialPort: Int = 0, val baudRate: Int = 9600) {
    companion object {
        fun read(context: Context): EcrSettings {
            val p = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
            return EcrSettings(p.getBoolean("ecr_enabled",false), p.getString("ecr_transport","TCP/IP")!!,
                p.getInt("ecr_port",9100),p.getBoolean("ecr_kiosk",false), p.getInt("ecr_serial_port",0), p.getInt("ecr_baud_rate",9600)).forDevice(android.os.Build.MODEL)
        }
    }
    fun forDevice(model: String): EcrSettings =
        if (transport == "USB") fixedEcrUsbPort(model)?.let { copy(serialPort = it) } ?: this else this

    fun save(context: Context) = synchronized(EcrRuntime) {
        check(!EcrRuntime.busy) { "ECR operation in progress" }
        require(port in 1024..65535)
        require(transport in setOf("TCP/IP", "USB", "RS232"))
        require(serialPort in 0..255 && baudRate in setOf(1200,2400,4800,9600,19200,38400,57600,115200))
        check(context.getSharedPreferences("app_preferences",Context.MODE_PRIVATE).edit()
            .putBoolean("ecr_enabled",enabled).putString("ecr_transport",transport)
            .putInt("ecr_serial_port",forDevice(android.os.Build.MODEL).serialPort).putInt("ecr_baud_rate",baudRate)
            .putInt("ecr_port",port).putBoolean("ecr_kiosk",kiosk).commit())
        EcrRuntime.configure(context, forDevice(android.os.Build.MODEL))
    }
}

/** Built-in CDC is wired to SDK port 0 on these terminals. RS232 is independent. */
internal fun fixedEcrUsbPort(model: String): Int? =
    if (model.replace(" ", "").uppercase() in setOf("CT20", "CT20P")) 0 else null
