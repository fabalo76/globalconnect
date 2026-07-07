package com.uic.uicpaymentapp.iso8583

import android.content.Context
import com.uic.pos.iso8583.IsoMessageFactory
import com.uic.pos.iso8583.config.IsoConfigParser
import com.uic.pos.iso8583.exception.Iso8583ParseException
import java.io.IOException

/**
 * Convenience access point for the ISO 8583 module. The provider loads configuration files stored
 * as Android assets and exposes ready-to-use [IsoMessageFactory] instances for the caller.
 */
object Iso8583Provider {

    private const val DEFAULT_CONFIG_ASSET = "iso8583_ISSWITCH_config.xml"

    /**
     * Reads the ISO 8583 configuration bundled with the UIC build variant (or the specified
     * override) and creates an [IsoMessageFactory].
     *
     * @param context Android context used to resolve the asset stream.
     * @param assetName Name of the asset that contains the ISO 8583 XML definition. Defaults to the
     * UIC profile when omitted.
     * @return the configured message factory or `null` if the configuration could not be loaded.
     */
    @JvmStatic
    @JvmOverloads
    fun createFactory(
        context: Context,
        assetName: String = DEFAULT_CONFIG_ASSET,
    ): IsoMessageFactory? = try {
        context.assets.open(assetName).use { stream ->
            IsoConfigParser.fromStream(stream)
        }
    } catch (error: IOException) {
        null
    } catch (error: Iso8583ParseException) {
        null
    }
}
