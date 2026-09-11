package one.globalconnect.xtmsagent.mqtt.tls

import android.content.Context
import android.util.Base64
import android.util.Log
import one.globalconnect.xtmsagent.TMSFunc
import one.globalconnect.xtmsagent.launcher.LauncherConfigManager
import one.globalconnect.xtmsagent.net.DeviceApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import java.util.concurrent.TimeUnit

private const val TAG = "AwsIotCertStore"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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

    fun refresh(serialNumber: String) {
        provision(serialNumber)
    }

    fun clear() {
        val failures = listOf(privateKeyFile, certificateFile)
            .filter { it.exists() && !it.delete() }
        if (failures.isNotEmpty()) {
            throw IllegalStateException(
                "Could not clear AWS IoT credential files: ${failures.joinToString { it.name }}",
            )
        }
        Log.i(TAG, "Local AWS IoT credentials cleared")
    }

    fun resetServerRegistration(serialNumber: String) {
        val cfg = TMSFunc.tmsCfg
        val token = DeviceApi.deviceToken(serialNumber, cfg)
        val path = "/v1/devices/${serialNumber.urlEncode()}/iot-credentials/reset"
        val url = DeviceApi.primaryUrl(path)
        val client = OkHttpClient.Builder()
            .connectTimeout(cfg.conn_timeout.toLong(), TimeUnit.SECONDS)
            .readTimeout(cfg.resp_timeout.toLong(), TimeUnit.SECONDS)
            .callTimeout((cfg.conn_timeout + cfg.resp_timeout).toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Device $token")
            .header("Connection", "close")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("IoT registration reset HTTP ${response.code}: $responseBody")
            }
            Log.i(TAG, "AWS IoT server registration reset for $serialNumber")
        }
    }

    private fun provision(serialNumber: String) {
        val cfg = TMSFunc.tmsCfg
        val token = DeviceApi.deviceToken(serialNumber, cfg)
        val path = "/v1/devices/${serialNumber.urlEncode()}/iot-credentials"
        val url = DeviceApi.primaryUrl(path)
        provisionFromUrl(serialNumber, token, url)
    }

    private fun provisionFromUrl(serialNumber: String, token: String, url: String) {
        val cfg = TMSFunc.tmsCfg
        val client = OkHttpClient.Builder()
            .connectTimeout(cfg.conn_timeout.toLong(), TimeUnit.SECONDS)
            .readTimeout(cfg.resp_timeout.toLong(), TimeUnit.SECONDS)
            .callTimeout((cfg.conn_timeout + cfg.resp_timeout).toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Device $token")
            .header("Connection", "close")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("IoT credentials HTTP ${response.code}: $responseBody")
            }

            val json = JSONObject(responseBody)
            val certificatePem = json.optString("certificatePem", json.optString("CertificatePem"))
            val privateKeyPem = json.optString("privateKey", json.optString("PrivateKey"))
            if (certificatePem.isBlank() || privateKeyPem.isBlank()) {
                throw IllegalStateException("IoT credentials response is missing certificate material")
            }

            buildKeyManagerFactory(certificatePem, privateKeyPem)
            // A newly issued certificate can follow a bank transfer, so the launcher
            // assignment cached on the terminal must not be trusted. Persist the
            // requirement before saving the certificate to make the two operations
            // crash-safe: if the marker cannot be stored, provisioning is retried.
            LauncherConfigManager.markRefreshRequiredAfterIotProvisioning(context)
            certDir.mkdirs()
            writeCredentialFile(certificateFile, certificatePem)
            writeCredentialFile(privateKeyFile, privateKeyPem)
            Log.i(TAG, "AWS IoT certificate provisioned for $serialNumber from $url")
        }
    }

    private fun writeCredentialFile(target: File, contents: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.outputStream().use { stream ->
            stream.write(contents.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
        temp.copyTo(target, overwrite = true)
        temp.delete()
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

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
