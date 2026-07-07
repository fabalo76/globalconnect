package one.globalconnect.xtmsagent.mqtt.tls

import android.content.Context
import android.util.Base64
import android.util.Log
import one.globalconnect.xtmsagent.TMSFunc
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory

private const val TAG = "AwsIotCertStore"
private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 30_000

class AwsIotCertificateStore(private val context: Context) {

    private val certDir = File(context.filesDir, "aws_iot")
    private val certificateFile = File(certDir, "device.crt")
    private val privateKeyFile = File(certDir, "device.key")

    fun getOrProvision(serialNumber: String): KeyManagerFactory {
        if (!certificateFile.exists() || !privateKeyFile.exists()) {
            provision(serialNumber)
        }

        val certificatePem = certificateFile.readText(Charsets.UTF_8)
        val privateKeyPem = privateKeyFile.readText(Charsets.UTF_8)
        return buildKeyManagerFactory(certificatePem, privateKeyPem)
    }

    fun clear() {
        certificateFile.delete()
        privateKeyFile.delete()
    }

    private fun provision(serialNumber: String) {
        val cfg = TMSFunc.tmsCfg
        val token = deviceToken(serialNumber, cfg.download_secret)
        val url = "${cfg.webScheme}://${cfg.apiHost}:${cfg.web_port}/v1/devices/${serialNumber.urlEncode()}/iot-credentials"
        val conn = URL(url).openConnection() as HttpURLConnection

        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Authorization", "Device $token")
            conn.doOutput = true
            conn.doInput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw IllegalStateException("IoT credentials HTTP ${conn.responseCode}: $err")
            }

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val certificatePem = json.optString("certificatePem", json.optString("CertificatePem"))
            val privateKeyPem = json.optString("privateKey", json.optString("PrivateKey"))
            if (certificatePem.isBlank() || privateKeyPem.isBlank()) {
                throw IllegalStateException("IoT credentials response is missing certificate material")
            }

            certDir.mkdirs()
            certificateFile.writeText(certificatePem, Charsets.UTF_8)
            privateKeyFile.writeText(privateKeyPem, Charsets.UTF_8)
            Log.i(TAG, "AWS IoT certificate provisioned for $serialNumber")
        } finally {
            conn.disconnect()
        }
    }

    private fun buildKeyManagerFactory(certificatePem: String, privateKeyPem: String): KeyManagerFactory {
        val certificate = parseCertificate(certificatePem)
        val privateKey = parsePrivateKey(privateKeyPem)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).also {
            it.load(null, null)
            it.setKeyEntry("aws-iot-device", privateKey, CharArray(0), arrayOf(certificate))
        }

        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).also {
            it.init(keyStore, CharArray(0))
        }
    }

    private fun parseCertificate(pem: String): X509Certificate {
        val der = readPemBlock(pem)
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun parsePrivateKey(pem: String): PrivateKey {
        val der = readPemBlock(pem)
        val pkcs8 = if (pem.contains("BEGIN RSA PRIVATE KEY")) wrapRsaPrivateKey(der) else der
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    private fun readPemBlock(pem: String): ByteArray {
        val base64 = pem
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .trim()
        return Base64.decode(base64, Base64.DEFAULT)
    }

    private fun wrapRsaPrivateKey(pkcs1: ByteArray): ByteArray {
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val rsaAlgorithmIdentifier = byteArrayOf(
            0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(),
            0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
        )
        val privateKeyOctetString = byteArrayOf(0x04) + encodeLength(pkcs1.size) + pkcs1
        val body = version + rsaAlgorithmIdentifier + privateKeyOctetString
        return byteArrayOf(0x30) + encodeLength(body.size) + body
    }

    private fun encodeLength(length: Int): ByteArray {
        if (length < 128) return byteArrayOf(length.toByte())
        var value = length
        val bytes = ArrayList<Byte>()
        while (value > 0) {
            bytes.add(0, (value and 0xff).toByte())
            value = value shr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    private fun deviceToken(serial: String, secret: String): String {
        if (secret.isBlank()) {
            throw IllegalStateException("Device download secret is missing")
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(serial.trim().uppercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
