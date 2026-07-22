package one.globalconnect.paymentapp.uicpos.pos.host

import android.util.Log
import com.uic.pos.iso8583.IsoMessageFactory
import com.uic.pos.iso8583.config.IsoConfigParser
import com.uic.pos.iso8583.exception.Iso8583ParseException
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Lazily loads the ISO8583 field configuration used by the host protocols.
 */
object IsoMessageFactoryProvider {

    private const val DEFAULT_CONFIG_ASSET = "iso8583_ISSWITCH_config.xml"
    private const val TAG = "IsoFactoryProvider"

    private val cache = ConcurrentHashMap<String, IsoMessageFactory>()

    /**
     * Returns the [IsoMessageFactory] configured from the given asset file.
     *
     * The provider caches the factories per asset so repeated calls do not
     * reload the configuration.
     */
    @JvmStatic
    @JvmOverloads
    fun factoryFor(assetName: String = DEFAULT_CONFIG_ASSET): IsoMessageFactory {
        cache[assetName]?.let {
            Log.d(TAG, "Using cached ISO8583 configuration for asset=$assetName")
            return it
        }

        return cache.computeIfAbsent(assetName) { asset ->
            Log.d(TAG, "Loading ISO8583 configuration from asset=$asset")
            val assets = GlobalConnectPaymentApplication.instance.assets
            try {
                assets.open(asset).use { stream ->
                    IsoConfigParser.fromStream(stream)
                }
            } catch (error: IOException) {
                Log.e(TAG, "Unable to read ISO8583 configuration", error)
                throw HostProtocolException("Unable to load ISO8583 configuration", error)
            } catch (error: Iso8583ParseException) {
                Log.e(TAG, "Invalid ISO8583 configuration", error)
                throw HostProtocolException("Invalid ISO8583 configuration", error)
            }
        }
    }
}
