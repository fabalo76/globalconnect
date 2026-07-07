package one.globalconnect.xtmsagent.remote

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object KinesisSigV4Signer {
    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val SERVICE = "kinesisvideo"
    private const val REQUEST_TYPE = "aws4_request"

    fun signMasterUrl(
        wssEndpoint: String,
        channelArn: String,
        region: String,
        credentials: RemoteControlCredentials,
        now: Long = System.currentTimeMillis(),
    ): String {
        val endpoint = URI(wssEndpoint)
        val dateStamp = formatUtc("yyyyMMdd", now)
        val amzDate = formatUtc("yyyyMMdd'T'HHmmss'Z'", now)
        val credentialScope = "$dateStamp/$region/$SERVICE/$REQUEST_TYPE"

        val queryParams = sortedMapOf(
            "X-Amz-Algorithm" to ALGORITHM,
            "X-Amz-ChannelARN" to channelArn,
            "X-Amz-Credential" to "${credentials.accessKeyId}/$credentialScope",
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to "299",
            "X-Amz-Security-Token" to credentials.sessionToken,
            "X-Amz-SignedHeaders" to "host",
        )

        val canonicalQuery = queryParams.entries.joinToString("&") {
            "${percentEncode(it.key)}=${percentEncode(it.value)}"
        }
        val canonicalRequest = listOf(
            "GET",
            "/",
            canonicalQuery,
            "host:${endpoint.host}\n",
            "host",
            sha256Hex(""),
        ).joinToString("\n")
        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            credentialScope,
            sha256Hex(canonicalRequest),
        ).joinToString("\n")
        val signingKey = signingKey(credentials.secretAccessKey, dateStamp, region)
        val signature = hmacSha256(stringToSign, signingKey).toHex()
        return "${endpoint.scheme}://${endpoint.host}/?$canonicalQuery&X-Amz-Signature=$signature"
    }

    private fun formatUtc(pattern: String, millis: Long): String {
        return SimpleDateFormat(pattern, Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))
    }

    private fun signingKey(secretAccessKey: String, dateStamp: String, region: String): ByteArray {
        val dateKey = hmacSha256(dateStamp, "AWS4$secretAccessKey".toByteArray(StandardCharsets.UTF_8))
        val regionKey = hmacSha256(region, dateKey)
        val serviceKey = hmacSha256(SERVICE, regionKey)
        return hmacSha256(REQUEST_TYPE, serviceKey)
    }

    private fun hmacSha256(data: String, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sha256Hex(data: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray(StandardCharsets.UTF_8))
            .toHex()
    }

    private fun percentEncode(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val out = StringBuilder(bytes.size * 3)
        for (byte in bytes) {
            val unsigned = byte.toInt() and 0xff
            val ch = unsigned.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                out.append(ch)
            } else {
                out.append('%')
                out.append(unsigned.toString(16).uppercase(Locale.US).padStart(2, '0'))
            }
        }
        return out.toString()
    }

    private fun ByteArray.toHex(): String = joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
