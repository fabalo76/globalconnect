package one.globalconnect.xtmsagent.mqtt

import one.globalconnect.xtmsagent.R

internal fun TmsConnectionStatus.linkIcon(): Int = when {
    connected -> R.drawable.ic_tms_link_connected
    severity == TmsStatusSeverity.CONNECTING -> R.drawable.ic_tms_link_connecting
    else -> R.drawable.ic_tms_link_disconnected
}

internal fun TmsConnectionStatus.linkColor(): Int = when {
    connected -> 0xFF2E7D32.toInt()
    severity == TmsStatusSeverity.CONNECTING -> 0xFF9C7400.toInt()
    else -> 0xFFB71C1C.toInt()
}

internal fun TmsConnectionStatus.linkLabel(): String = when {
    connected -> "IoT connected"
    severity == TmsStatusSeverity.CONNECTING -> "IoT connecting"
    else -> "IoT disconnected"
}
