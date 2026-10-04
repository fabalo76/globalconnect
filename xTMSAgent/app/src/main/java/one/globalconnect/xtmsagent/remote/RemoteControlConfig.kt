package one.globalconnect.xtmsagent.remote

import org.json.JSONObject

/** Parameters delivered by the MQTT remote_start command for Kinesis WebRTC. */
data class RemoteControlConfig(
    val sessionId: String,
    val terminalId: String,
    val provider: String,
    val region: String,
    val channelName: String,
    val channelArn: String,
    val clientId: String,
    val viewerClientId: String?,
    val endpoints: Map<String, String>,
    val iceServers: List<RemoteControlIceServer>,
    val credentials: RemoteControlCredentials,
    val viewerTimeoutSeconds: Int = 120,
    val quality: String = "low",
    val maxQuality: String = "high",
) {
    companion object {
        fun fromJson(payload: String, terminalId: String): RemoteControlConfig {
            return fromJson(JSONObject(payload), terminalId)
        }

        fun fromJson(json: JSONObject, terminalId: String): RemoteControlConfig {
            val provider = json.optString("provider", "")
            require(provider == "kinesis-webrtc" || provider == "standalone-relay") { "unsupported remote control provider" }

            val endpointsJson = json.getJSONObject("endpoints")
            val endpoints = buildMap {
                val keys = endpointsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key.uppercase(), endpointsJson.getString(key))
                }
            }

            val iceServerArray = json.optJSONArray("iceServers")
            val iceServers = buildList {
                if (iceServerArray != null) {
                    for (index in 0 until iceServerArray.length()) {
                        val iceJson = iceServerArray.getJSONObject(index)
                        val uriArray = iceJson.getJSONArray("uris")
                        val uris = buildList {
                            for (uriIndex in 0 until uriArray.length()) add(uriArray.getString(uriIndex))
                        }
                        add(
                            RemoteControlIceServer(
                                uris = uris,
                                username = iceJson.optString("username").ifBlank { null },
                                password = iceJson.optString("password").ifBlank { null },
                            )
                        )
                    }
                }
            }

            val credentialsJson = if (provider == "kinesis-webrtc") json.getJSONObject("credentials") else JSONObject()
            return RemoteControlConfig(
                sessionId = json.getString("sessionId"),
                terminalId = terminalId,
                provider = provider,
                region = json.getString("region"),
                channelName = json.optString("channelName"),
                channelArn = json.getString("channelArn"),
                clientId = json.optString("clientId", terminalId),
                viewerClientId = json.optString("viewerClientId").ifBlank { null },
                endpoints = endpoints,
                iceServers = iceServers,
                credentials = RemoteControlCredentials(
                    accessKeyId = if (provider == "kinesis-webrtc") credentialsJson.getString("accessKeyId") else "",
                    secretAccessKey = if (provider == "kinesis-webrtc") credentialsJson.getString("secretAccessKey") else "",
                    sessionToken = if (provider == "kinesis-webrtc") credentialsJson.getString("sessionToken") else "",
                ),
                viewerTimeoutSeconds = json.optInt("timeout", 120).coerceIn(15, 600),
                quality = json.optString("quality", "low"),
                maxQuality = json.optString("maxQuality", "high"),
            )
        }
    }
}

data class RemoteControlIceServer(
    val uris: List<String>,
    val username: String?,
    val password: String?,
)

data class RemoteControlCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String,
)
