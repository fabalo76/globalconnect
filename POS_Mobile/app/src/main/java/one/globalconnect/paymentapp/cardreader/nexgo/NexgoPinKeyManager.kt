package one.globalconnect.paymentapp.cardreader.nexgo

import android.util.Log
import com.nexgo.common.ByteUtils
import com.nexgo.oaf.apiv3.DeviceEngine
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.device.pinpad.AlgorithmModeEnum
import com.nexgo.oaf.apiv3.device.pinpad.PinPadTypeEnum
import com.nexgo.oaf.apiv3.device.pinpad.WorkKeyTypeEnum
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_PinKeyScheme

internal data class PinKeyApplyResult(
    val success: Boolean,
    val message: String,
)

internal object NexgoPinKeyManager {
    private const val TAG = "NexgoPinKeyManager"

    fun apply(deviceEngine: DeviceEngine, acquirers: List<TMS_Acquirer>): PinKeyApplyResult {
        val supported = acquirers.filter { it.supportsOnlinePin }
        if (supported.isEmpty()) return PinKeyApplyResult(true, "No online PIN profiles configured")

        val invalidIndex = supported.firstOrNull { it.nexgoPinKeyIndex == null }
        if (invalidIndex != null) {
            return PinKeyApplyResult(false, "Acquirer ${invalidIndex.AcqID} has an invalid PIN key index; Nexgo index must resolve to 1-10")
        }

        val sessionKeysByIndex = supported
            .filter { it.pinKeyScheme == TMS_PinKeyScheme.MKSK }
            .mapNotNull { acquirer -> acquirer.encryptedPinSessionKey?.let { acquirer.nexgoPinKeyIndex!! to it } }
            .groupBy({ it.first }, { it.second })
        val conflict = sessionKeysByIndex.entries.firstOrNull { (_, keys) -> keys.distinct().size > 1 }
        if (conflict != null) {
            return PinKeyApplyResult(false, "Multiple encrypted PIN session keys target Nexgo index ${conflict.key}")
        }
        val invalidSession = sessionKeysByIndex.values.flatten().firstOrNull { key ->
            key.length !in setOf(32, 48) || key.any { it !in '0'..'9' && it.uppercaseChar() !in 'A'..'F' }
        }
        if (invalidSession != null) {
            return PinKeyApplyResult(false, "An encrypted PIN session key must contain 32 or 48 hexadecimal characters")
        }

        return runCatching {
            val pinPad = deviceEngine.pinPad
            pinPad.initPinPad(PinPadTypeEnum.INTERNAL)

            sessionKeysByIndex.forEach { (index, keys) ->
                val encryptedSessionKey = ByteUtils.hexString2ByteArray(keys.single())
                pinPad.setAlgorithmMode(AlgorithmModeEnum.DES)
                val result = pinPad.writeWKey(
                    index,
                    WorkKeyTypeEnum.PINKEY,
                    encryptedSessionKey,
                    encryptedSessionKey.size,
                )
                if (result != SdkResult.Success) {
                    error("Nexgo rejected the encrypted PIN session key for index $index (SDK $result)")
                }
                Log.i(TAG, "Encrypted MK/SK PIN session key applied at Nexgo index $index")
            }

            supported.forEach { acquirer ->
                val index = acquirer.nexgoPinKeyIndex!!
                when (acquirer.pinKeyScheme) {
                    TMS_PinKeyScheme.MKSK -> {
                        if (!pinPad.isKeyExist(index, WorkKeyTypeEnum.PINKEY)) {
                            error("No PEK/PIN session key is loaded at Nexgo index $index for acquirer ${acquirer.AcqID}")
                        }
                        if (acquirer.encryptedPinSessionKey == null) {
                            Log.i(
                                TAG,
                                "Using PIN key already loaded at Nexgo index $index for acquirer ${acquirer.AcqID}",
                            )
                        }
                    }
                    TMS_PinKeyScheme.DUKPT -> {
                        pinPad.setAlgorithmMode(AlgorithmModeEnum.DUKPT)
                        if (pinPad.dukptCurrentKsn(index) == null) {
                            error("No DUKPT Initial Key is loaded at Nexgo index $index for acquirer ${acquirer.AcqID}")
                        }
                        Log.i(TAG, "Validated DUKPT Initial Key at Nexgo index $index for acquirer ${acquirer.AcqID}")
                    }
                    TMS_PinKeyScheme.NONE -> Unit
                }
            }
            PinKeyApplyResult(true, "Validated ${supported.size} online PIN profile(s)")
        }.getOrElse { error ->
            Log.e(TAG, "Unable to apply online PIN key configuration: ${error.message}", error)
            PinKeyApplyResult(false, error.message ?: "Unable to apply online PIN key configuration")
        }
    }
}
