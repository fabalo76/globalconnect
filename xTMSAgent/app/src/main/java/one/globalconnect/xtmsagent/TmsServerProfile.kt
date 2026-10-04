package one.globalconnect.xtmsagent

import java.security.MessageDigest

/** Connection profiles share the device protocol, but never share credentials. */
enum class TmsServerProfile(val id: String) {
    AWS("aws"), DEMO("demo");

    companion object {
        const val DEMO_HOST = "demo.globalconnect.one"

        fun current(config: TMSFunc.tms): TmsServerProfile =
            if (config.apiHost.equals(DEMO_HOST, ignoreCase = true)) DEMO else AWS

        // Preserve existing AWS storage so an upgrade does not reset deployed terminals.
        fun storageName(name: String, config: TMSFunc.tms = TMSFunc.tmsCfg): String =
            if (current(config) == AWS) name else "${name}_${scope(config)}"

        fun scope(config: TMSFunc.tms): String = MessageDigest.getInstance("SHA-256")
            .digest("${config.webScheme}://${config.apiHost.lowercase()}:${config.web_port}".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun defaults(): TMSFunc.tms = when (this) {
        AWS -> TMSFunc.tms(web_port = 443)
        DEMO -> TMSFunc.tms(
            server_addr = DEMO_HOST,
            api_host = DEMO_HOST,
            api_host_fallback = "",
            mqtt_host = DEMO_HOST,
            web_port = 443,
            download_credential_id = "",
            download_secret = "",
        )
    }
}
