package one.globalconnect.paymentapp.uicpos.pos.host

import android.util.Base64
import android.util.Log
import com.uic.pos.iso8583.IsoLengthType
import one.globalconnect.tms.paymentapp.TMS_HostConnectionInfo
import one.globalconnect.tms.paymentapp.TMS_Terminal
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.text.Charsets

/**
 * Represents a host endpoint using host name / IP and port.
 */
data class HostAddress(val host: String, val port: Int) {
    val displayValue: String
        get() = if (port > 0) "$host:$port" else host
}

data class HostEndpoint(val address: HostAddress, val type: EndpointType) {
    enum class EndpointType { PRIMARY, SECONDARY }
}

/**
 * Length prefix configuration used when framing ISO8583 messages.
 */
data class LengthConfig(
    val lengthBytes: Int,
    val lengthType: IsoLengthType,
)

/**
 * Resolves the length prefix configuration for a given host protocol.
 */
object LengthPrefixRegistry {
    private val hostOverrides = mapOf<Long, Int>(
        HostProtocolRegistry.ISSWITCH to 2,
        HostProtocolRegistry.BANPAIS_GL to 2,
        HostProtocolRegistry.BANPAIS_IO to 2,
    )

    fun resolve(hostProtocol: Long, terminal: TMS_Terminal?): LengthConfig {
        val bytes = hostOverrides[hostProtocol] ?: DEFAULT_LENGTH_BYTES
        val type = if (terminal?.IsBCDLength == true) IsoLengthType.BCD else IsoLengthType.HEX
        return LengthConfig(bytes, type)
    }

    private const val DEFAULT_LENGTH_BYTES = 2
}

fun parseHostAddress(raw: String?): HostAddress? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (value.startsWith("[")) {
        val closing = value.indexOf(']')
        if (closing > 0 && closing + 1 < value.length && value[closing + 1] == ':') {
            val host = value.substring(1, closing)
            val port = value.substring(closing + 2).toIntOrNull() ?: 0
            HostAddress(host, port)
        } else {
            HostAddress(value.removePrefix("[").removeSuffix("]"), 0)
        }
    } else {
        val lastColon = value.lastIndexOf(':')
        if (lastColon > 0) {
            val host = value.substring(0, lastColon)
            val port = value.substring(lastColon + 1).toIntOrNull()
            if (port != null) {
                HostAddress(host, port)
            } else {
                HostAddress(value, 0)
            }
        } else {
            HostAddress(value, 0)
        }
    }
}

/**
 * Decodes a TMS binary field (SSLCACertificate, SSLClientPubCert, SSLClientPrivKey).
 *
 * Wire format (after base64 decode):
 *   [1 byte: filename length N] [N bytes: filename] [remaining bytes: file content]
 *
 * Returns a pair of (filename, fileContent), or null if the value is absent or malformed.
 */
fun decodeTmsField(base64Value: String?): Pair<String, ByteArray>? {
    if (base64Value.isNullOrBlank()) return null
    val raw = try {
        Base64.decode(base64Value, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "TMS field: invalid base64", e)
        return null
    }
    if (raw.isEmpty()) return null
    val rawByte = raw[0].toInt() and 0xFF
    val nameLen = (rawByte ushr 4) * 10 + (rawByte and 0x0F)
    Log.d(TAG, "TMS field: length byte=0x${rawByte.toString(16).padStart(2,'0')} (BCD) -> nameLen=$nameLen")
    if (raw.size < 1 + nameLen) {
        Log.e(TAG, "TMS field: declared filename length $nameLen exceeds available bytes ${raw.size - 1}")
        return null
    }
    val filename = String(raw, 1, nameLen, Charsets.ISO_8859_1)
    val content  = raw.copyOfRange(1 + nameLen, raw.size)
    return Pair(filename, content)
}

fun decodeCertificates(raw: String?): List<X509Certificate> {
    if (raw.isNullOrBlank()) return emptyList()
    if (raw.contains("-----BEGIN CERTIFICATE-----")) {
        return parseCertificates(raw.byteInputStream(Charsets.US_ASCII), "pem")
    }
    val (filename, content) = decodeTmsField(raw) ?: return emptyList()
    return parseCertificates(ByteArrayInputStream(content), filename)
}

private fun parseCertificates(input: InputStream, filename: String): List<X509Certificate> {
    return try {
        val certs = CertificateFactory.getInstance("X.509")
            .generateCertificates(input)
            .filterIsInstance<X509Certificate>()
        Log.d(TAG, "Loaded ${certs.size} certificate(s) from TMS field (file=$filename)")
        certs.forEachIndexed { i, cert -> logCertificate(TAG, "  TMS[$i]", cert) }
        certs
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse certificate(s) from TMS field (file=$filename)", e)
        emptyList()
    }
}

private fun logCertificate(tag: String, label: String, cert: X509Certificate) {
    Log.d(tag, "$label subject=${cert.subjectX500Principal.name}")
    Log.d(tag, "$label issuer=${cert.issuerX500Principal.name}")
    Log.d(tag, "$label validity=${cert.notBefore} → ${cert.notAfter}")
}

fun loadCertificatesFromStream(stream: InputStream): List<X509Certificate> =
    try {
        CertificateFactory.getInstance("X.509")
            .generateCertificates(stream)
            .filterIsInstance<X509Certificate>()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load certificates from stream", e)
        emptyList()
    }

/**
 * Holds CA certificates bundled in res/raw/trusted_cas.crt.
 * Set once at app startup; merged with system trust store when no TMS certs are configured.
 */
object BundledCertificates {
    @Volatile private var certs: List<X509Certificate> = emptyList()
    fun set(certificates: List<X509Certificate>) { certs = certificates }
    fun get(): List<X509Certificate> = certs
}

/**
 * Per-acquirer SSL socket factories built once when TMS parameters load.
 * Keyed by IPTab ID. Rebuilt on every TMS update via [build].
 */
object AcquirerSslCache {
    @Volatile private var cache: Map<String, SSLSocketFactory> = emptyMap()

    fun build(ipTabs: List<TMS_HostConnectionInfo>, bundledCerts: List<X509Certificate>) {
        val built = mutableMapOf<String, SSLSocketFactory>()
        for (ipTab in ipTabs) {
            if (!ipTab.SSL) continue
            val tmsCerts = decodeCertificates(ipTab.SSLCACertificate)
            val trustManagers = if (ipTab.ignoreTlsTrustErrors) {
                Log.w(TAG, "TLS trust validation disabled for IPTab=${ipTab.IPTabID}")
                buildTrustAllManagers()
            } else {
                buildMergedTrustManagers(tmsCerts, bundledCerts, TAG)
            }
            val ctx = SSLContext.getInstance("TLSv1.2")
            ctx.init(null, trustManagers, null)
            built[ipTab.IPTabID.toString()] = ctx.socketFactory
            Log.d(
                TAG,
                "AcquirerSslCache: built factory for IPTab=${ipTab.IPTabID} " +
                    "ignoreTlsTrustErrors=${ipTab.ignoreTlsTrustErrors} (${tmsCerts.size} TMS CA(s))"
            )
        }
        cache = built
        Log.d(TAG, "AcquirerSslCache: ${built.size} SSL profile(s) cached")
    }

    fun get(ipTabId: String): SSLSocketFactory? = cache[ipTabId]
}

/**
 * Builds TLS trust managers by merging all three CA sources:
 *  1. IPTab SSLCACertificate certs (per-acquirer, downloaded via TMS)
 *  2. Bundled app certs (res/raw/trusted_cas.crt — CAs missing from Android system store)
 *  3. Android system trust store (well-known public CAs)
 */
fun buildMergedTrustManagers(
    tmsCertificates: List<X509Certificate>,
    bundledCertificates: List<X509Certificate>,
    tag: String,
): Array<TrustManager> {
    val systemFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    systemFactory.init(null as KeyStore?)
    val systemTm = systemFactory.trustManagers.filterIsInstance<X509TrustManager>().first()

    val customCerts = tmsCertificates + bundledCertificates
    if (customCerts.isEmpty()) {
        Log.d(tag, "TLS: no custom CAs; using system trust store only")
        return systemFactory.trustManagers
    }

    val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    tmsCertificates.forEachIndexed { i, c -> ks.setCertificateEntry("tms_$i", c) }
    bundledCertificates.forEachIndexed { i, c -> ks.setCertificateEntry("bundled_$i", c) }
    val customFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    customFactory.init(ks)
    val customTm = customFactory.trustManagers.filterIsInstance<X509TrustManager>().first()

    Log.d(tag, "TLS trust store: ${tmsCertificates.size} TMS CA(s) + ${bundledCertificates.size} bundled CA(s) + system")
    tmsCertificates.forEachIndexed { i, c -> logCertificate(tag, "  TMS CA[$i]", c) }
    bundledCertificates.forEachIndexed { i, c -> logCertificate(tag, "  Bundled CA[$i]", c) }
    return arrayOf(CompositeTrustManager(customTm, systemTm))
}

fun buildTrustAllManagers(): Array<TrustManager> =
    arrayOf(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    )

private class CompositeTrustManager(
    private val primary: X509TrustManager,
    private val fallback: X509TrustManager,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        try { primary.checkClientTrusted(chain, authType) }
        catch (e: CertificateException) { fallback.checkClientTrusted(chain, authType) }
    }
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try { primary.checkServerTrusted(chain, authType) }
        catch (e: CertificateException) { fallback.checkServerTrusted(chain, authType) }
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> =
        primary.acceptedIssuers + fallback.acceptedIssuers
}

private const val TAG = "HostNetworkUtils"
