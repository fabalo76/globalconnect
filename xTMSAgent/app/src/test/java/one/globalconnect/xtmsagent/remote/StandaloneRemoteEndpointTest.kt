package one.globalconnect.xtmsagent.remote

import org.junit.Assert.*
import org.junit.Test

class StandaloneRemoteEndpointTest {
    private val token = "A".repeat(64)
    private val path = "/standalone/remote/session/master"
    @Test fun extractsTokenWithoutSendingItInTheUrl() {
        val (url, parsed) = StandaloneRemoteEndpoint.parse("wss://demo.globalconnect.one$path#token=$token", "demo.globalconnect.one", "session")
        assertEquals("wss://demo.globalconnect.one$path", url)
        assertEquals(token, parsed)
    }
    @Test fun refusesInsecureForeignAndWrongRoleEndpoints() {
        for (url in listOf("ws://demo.globalconnect.one$path#token=$token", "wss://other.example$path#token=$token",
            "wss://demo.globalconnect.one/standalone/remote/session/viewer#token=$token",
            "wss://demo.globalconnect.one$path?token=$token", "wss://demo.globalconnect.one$path#token=bad")) {
            try { StandaloneRemoteEndpoint.parse(url, "demo.globalconnect.one", "session"); fail("Accepted invalid endpoint") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun acceptsStandaloneWithoutAwsCredentialsAndPreservesAwsValidation() {
        val json = org.json.JSONObject().put("provider", "standalone-relay").put("sessionId", "session")
            .put("region", "local").put("channelArn", "session").put("endpoints", org.json.JSONObject().put("WSS", "wss://demo.globalconnect.one$path#token=$token"))
            .put("credentials", org.json.JSONObject.NULL)
        assertEquals("standalone-relay", RemoteControlConfig.fromJson(json, "N96").provider)
        json.put("provider", "kinesis-webrtc")
        try { RemoteControlConfig.fromJson(json, "N96"); fail("AWS credentials must remain mandatory") }
        catch (_: org.json.JSONException) { }
    }
}
