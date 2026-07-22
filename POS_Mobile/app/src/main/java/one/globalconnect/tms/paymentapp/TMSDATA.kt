package one.globalconnect.tms.paymentapp

import org.json.JSONObject
import kotlin.jvm.JvmName

data class TMSDATA(
    val applicationId: String = "",
    val schemaVersion: Int = 0,
    val host_connection_info: List<TMS_HostConnectionInfo> = emptyList(),
    val emv_ctls_config: List<TMS_EmvCtlsConfig> = emptyList(),
    val emv_contact_config: List<TMS_EmvContactConfig> = emptyList(),
    val card_ranges: List<TMS_CardRanges> = emptyList(),
    val terminal: List<TMS_Terminal> = emptyList(),
    val rawJson: String = ""
) {
    val IPTab: List<TMS_HostConnectionInfo> get() = host_connection_info
    val AIDtab: List<TMS_EmvContactConfig> get() = emv_contact_config
    val PCDApps: List<TMS_EmvCtlsConfig> get() = emv_ctls_config
    val CardRanges: List<TMS_CardRanges> get() = card_ranges
    val CardRange: List<TMS_CardRanges> get() = card_ranges
    @get:JvmName("getLegacyTerminal")
    val Terminal get() = terminal
    val Acquirer get() = terminal.flatMap { it.acquirer }
    val Issuer get() = Acquirer.flatMap { acquirer -> acquirer.issuer.map { it.copy(acquirerId = acquirer.AcqID) } }

    companion object {
        const val APPLICATION_NAME: String = "PAYMENT_APP"

        fun parse(jsonText: String): TMSDATA = fromJson(JSONObject(jsonText)).copy(rawJson = jsonText)

        fun fromJson(json: JSONObject): TMSDATA {
            val catalogTables = json.optJSONObject("catalogTables") ?: JSONObject()
            val tree = json.optJSONObject("tree") ?: JSONObject()
            return TMSDATA(
                applicationId = TMS_Json.readString(json, "applicationId"),
                schemaVersion = TMS_Json.readInt(json, "schemaVersion"),
                host_connection_info = TMS_HostConnectionInfo.listFromJson(catalogTables.optJSONArray("host_connection_info")),
                emv_ctls_config = TMS_EmvCtlsConfig.listFromJson(catalogTables.optJSONArray("emv_ctls_config")),
                emv_contact_config = TMS_EmvContactConfig.listFromJson(catalogTables.optJSONArray("emv_contact_config")),
                card_ranges = TMS_CardRanges.listFromJson(catalogTables.optJSONArray("card_ranges")),
                terminal = TMS_Terminal.listFromJson(tree.optJSONArray("terminal"))
            )
        }
    }
}
