package one.globalconnect.tms.paymentapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.jvm.JvmName

data class TMS_HostConnectionInfo(
    var config_id: String = "",
    var description: String = "",
    var primaryHost: String = "",
    var primaryPort: Int = 0,
    var primaryHostTries: Double = 0.0,
    var secondaryHost: String = "",
    var secondaryPort: Int = 0,
    var secondaryHostTries: Double = 0.0,
    var transport: String = "",
    var connectTimeoutSeconds: Double = 0.0,
    var timeoutSeconds: Double = 0.0,
    var primaryPhoneNumber: String = "",
    var secondaryPhoneNumber: String = "",
    var dialAttempts: Double = 0.0,
    var dialConnectTimeSeconds: Double = 0.0,
    var modemSetting: String = "",
    var ipAttempts: Double = 0.0,
    var primaryHostRetries: Double = 0.0,
    var secondaryHostRetries: Double = 0.0,
    var tlsCaCertificatePem: String = "",
    var tlsClientPublicCertPem: String = "",
    var tlsClientPrivateKeyPem: String = "",
    var tlsPrivateKeyPassword: String = "",
    var ignoreTlsTrustErrors: Boolean = false
) {
    val IPTabID: String get() = config_id
    val SSL: Boolean get() = transport.equals("tls", ignoreCase = true) || transport.equals("ssl", ignoreCase = true)
    val SSLCACertificate: String get() = tlsCaCertificatePem
    val PrimIpAddr: String get() = endpoint(primaryHost, primaryPort)
    val SecIpAddr: String get() = endpoint(secondaryHost, secondaryPort)
    val IPConnTime: Long get() = connectTimeoutSeconds.toLong()
    val TranTimeOut: Long get() = timeoutSeconds.toLong()
    val AttemptIPT: Long get() = ipAttempts.toLong()
    val IPConnRetriesP: Long get() = primaryHostRetries.toLong().takeIf { it > 0 } ?: primaryHostTries.toLong()
    val IPConnRetriesS: Long get() = secondaryHostRetries.toLong().takeIf { it > 0 } ?: secondaryHostTries.toLong()

    private fun endpoint(host: String, port: Int): String = when {
        host.isBlank() -> ""
        port > 0 && !host.contains(":") -> "$host:$port"
        else -> host
    }

    companion object {
        fun fromJson(json: JSONObject): TMS_HostConnectionInfo {
            return TMS_HostConnectionInfo(
                config_id = TMS_Json.readString(json, "config_id"),
                description = TMS_Json.readString(json, "description"),
                primaryHost = TMS_Json.readString(json, "primaryHost"),
                primaryPort = TMS_Json.readInt(json, "primaryPort"),
                primaryHostTries = TMS_Json.readDouble(json, "primaryHostTries"),
                secondaryHost = TMS_Json.readString(json, "secondaryHost"),
                secondaryPort = TMS_Json.readInt(json, "secondaryPort"),
                secondaryHostTries = TMS_Json.readDouble(json, "secondaryHostTries"),
                transport = TMS_Json.readString(json, "transport"),
                connectTimeoutSeconds = TMS_Json.readDouble(json, "connectTimeoutSeconds"),
                timeoutSeconds = TMS_Json.readDouble(json, "timeoutSeconds"),
                primaryPhoneNumber = TMS_Json.readString(json, "primaryPhoneNumber"),
                secondaryPhoneNumber = TMS_Json.readString(json, "secondaryPhoneNumber"),
                dialAttempts = TMS_Json.readDouble(json, "dialAttempts"),
                dialConnectTimeSeconds = TMS_Json.readDouble(json, "dialConnectTimeSeconds"),
                modemSetting = TMS_Json.readString(json, "modemSetting"),
                ipAttempts = TMS_Json.readDouble(json, "ipAttempts"),
                primaryHostRetries = TMS_Json.readDouble(json, "primaryHostRetries"),
                secondaryHostRetries = TMS_Json.readDouble(json, "secondaryHostRetries"),
                tlsCaCertificatePem = TMS_Json.readString(json, "tlsCaCertificatePem"),
                tlsClientPublicCertPem = TMS_Json.readString(json, "tlsClientPublicCertPem"),
                tlsClientPrivateKeyPem = TMS_Json.readString(json, "tlsClientPrivateKeyPem"),
                tlsPrivateKeyPassword = TMS_Json.readString(json, "tlsPrivateKeyPassword"),
                ignoreTlsTrustErrors = TMS_Json.readBoolean(json, "ignoreTlsTrustErrors")
            )
        }

        fun listFromJson(array: JSONArray?): List<TMS_HostConnectionInfo> {
            if (array == null) return emptyList()
            val items = mutableListOf<TMS_HostConnectionInfo>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                items.add(fromJson(item))
            }
            return items
        }
    }
}
