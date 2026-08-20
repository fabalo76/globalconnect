package one.globalconnect.keyinjection.util

import android.util.Log
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.device.pinpad.CalcModeEnum
import com.nexgo.oaf.apiv3.device.pinpad.DukptKeyTypeEnum
import com.nexgo.oaf.apiv3.device.pinpad.PinPad
import com.nexgo.oaf.apiv3.device.pinpad.PinPadTypeEnum
import com.nexgo.oaf.apiv3.device.pinpad.WorkKeyTypeEnum
import one.globalconnect.keyinjection.App
import one.globalconnect.keyinjection.R
import one.globalconnect.keyinjection.protocol.EPedKeyType


/**
 * Proxy utility for interacting with the PED.
 *
 * This centralises common PED related operations so they can be reused
 * across different screens.
 */
object PedProxy {
    val tAG = "PedProxy"
    private val clearWorkingKeyLock = Any()
    private var initted = false
    /** Obtain the internal PED instance or null if unavailable. */
    private val ped: PinPad?
        get() = App.deviceEngine.pinPad

    /**
     * Initialise the PED interface if it has not already been set up.
     *
     * @return `true` if the PED was initialised or was already ready
     */
    fun Init(): Boolean {
        if (initted) return true
        val ped = ped ?: return false
        val ret = ped.initPinPad(PinPadTypeEnum.INTERNAL)
        if (ret != SdkResult.Success) {
            Log.e(tAG, "Init. Error: [$ret]")
            return false
        }
        initted = true
        return true
    }

    /**
     * Calculate the KCV for a master key stored at [mKeyIdx].
     *
     * @param mKeyIdx master key slot index
     * @return first four bytes of the calculated KCV or `null` on error
     */
    fun calcMKeyKCV(mKeyIdx: Int): ByteArray? {
        return try {
            var tmp: ByteArray? = ByteArray(16)
            tmp = ped?.encryptByMKey(mKeyIdx, tmp, tmp!!.size)
            tmp?.copyOfRange(0, 4)
        } catch (e: Exception) {
            Log.e(tAG, "calcMKeyKCV. Exception")
            e.printStackTrace()
            null
        }
    }

    /**
     * Determine whether a key of [type] exists at the given [index].
     *
     * @param type key category to check
     * @param index storage slot to query
     * @return `true` if the key slot currently contains a key
     */
    fun isKeyLoaded(type: EPedKeyType, index: Int): Boolean {
        return try {
            when (type) {
                EPedKeyType.TMK -> ped?.isKeyExist(index) ?: false
                EPedKeyType.TPK -> ped?.isKeyExist(index, WorkKeyTypeEnum.PINKEY) ?: false // PIN Encryption
                EPedKeyType.TAK -> ped?.isKeyExist(index, WorkKeyTypeEnum.MACKEY) ?: false // MAC Generation
                EPedKeyType.TDK -> ped?.isKeyExist(index, WorkKeyTypeEnum.ENCRYPTIONKEY) ?: false // Data Encryption
                // EPedKeyType.TIK -> ped.  // DUKPT
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Determine if any keys are currently loaded in the PED.
     *
     * @return `true` if at least one key is present
     */
    fun hasExistingKeys(): Boolean {
        val ped = ped ?: return false
        val testBytes = ByteArray(8)
        val checkMode = 0.toByte()


        for (i in 1..10) { if (isKeyLoaded(EPedKeyType.TMK, i)) return true }
        for (i in 1..10) { if (isKeyLoaded(EPedKeyType.TPK, i)) return true }
        for (i in 1..10) { if (isKeyLoaded(EPedKeyType.TAK, i)) return true }
        for (i in 1..10) { if (isKeyLoaded(EPedKeyType.TDK, i)) return true }
        for (i in 1..10) { if (isKeyLoaded(EPedKeyType.TIK, i)) return true }

        return false
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
    /**
     * Retrieve the KCV for the key defined by [type] and [index].
     *
     * @param type key category to query
     * @param index storage slot to inspect
     * @return hexadecimal KCV string or `null` if the key is not present.
     */
    fun checkKey(type: EPedKeyType, index: Int): String? {
        val device = ped ?: return null

        return try {
            when (type) {
                EPedKeyType.TLK -> calcMKeyKCV(0)?.toHex()
                EPedKeyType.TMK -> calcMKeyKCV(index)?.toHex()
                EPedKeyType.TPK -> device.calcWKeyKCV(index, WorkKeyTypeEnum.PINKEY)?.toHex()          // PIN Encryption
                EPedKeyType.TAK -> device.calcWKeyKCV(index, WorkKeyTypeEnum.MACKEY)?.toHex()          // MAC Generation
                EPedKeyType.TDK -> device.calcWKeyKCV(index, WorkKeyTypeEnum.ENCRYPTIONKEY)?.toHex()   // Data Encryption
                EPedKeyType.TIK -> {
                    val ksnBytes = device.dukptCurrentKsn(index)
                    if (ksnBytes?.size == 10) "loaded" else null
                }
                else -> null

            }
        } catch (_: Exception) {
            null
        }
    }

    /** Result of attempting to load a key into the PED. */
    data class WriteKeyResult(
        /** Whether the PED reported success when loading the key. */
        val success: Boolean,
        /** ASCII response code returned by the PED. */
        val responseCode: String,
        /** ASCII response message in case of error. */
        val responseMessage: String
    )

    /**
     * Erase keys from the PED. The implementation is a placeholder that
     * currently always succeeds.
     *
     * @return always returns `true`
     */
    fun Erase(): Boolean {
        //return true  //Enable to disable the Key Erase.
        for (i in 0..10) {
            val kcv = checkKey(EPedKeyType.TMK, i)
            if (kcv != null) {
                ped?.deleteWKey(i, WorkKeyTypeEnum.PINKEY)
                ped?.deleteWKey(i, WorkKeyTypeEnum.ENCRYPTIONKEY)
                ped?.deleteWKey(i, WorkKeyTypeEnum.MACKEY)
                ped?.deleteWKey(i, WorkKeyTypeEnum.TDKEY)
                ped?.deleteMKey(i)
            }
        }

        // Delete DUKPT Keys not supported in the Nexgo SDK, neither a method to reset or erase the entire pinpad key slots.
        /*for (i in 1..10) {
            val tikKcv = PedProxy.checkKey(EPedKeyType.TIK, i)
            if (tikKcv != null) {
                // There should be a method for delete DUKP in the Nexgo SDK, but there is not such method.
            }
        }*/
        return true
    }

    /**
     * Inject a key into the PED.
     *
     * @param srcKeyType type of key used to protect the payload
     * @param srcKeyIndex index of the source key when applicable
     * @param dstKeyType destination key type to load
     * @param dstKeyIndex destination key index
     * @param dstKeyPayload raw key material to load
     * @param dstKSN key serial number for DUKPT keys (unused for clear keys)
     * @param dstKeyKCV expected key check value for the payload
     * @return [WriteKeyResult] describing whether the operation succeeded
     */
    fun writeKey(
        srcKeyType: EPedKeyType,
        srcKeyIndex: Int,
        dstKeyType: EPedKeyType,
        dstKeyIndex: Int,
        dstKeyPayload: ByteArray,
        dstKSN: ByteArray,
        dstKeyKCV : ByteArray
    ): WriteKeyResult {
        var responseCode = "FF"
        var responseMessage = "Key Load Failed"
        var keyLoaded = false
        val ped = ped ?: return WriteKeyResult(false, responseCode, responseMessage)
        //If Key is encrypted under a TLK (MK 0 for Nexgo) or a TMK
        if ((srcKeyType == EPedKeyType.TLK) || (srcKeyType == EPedKeyType.TMK))  {
            when (dstKeyType) {
                EPedKeyType.TMK -> {
                    val ret = ped.writeMKey(dstKeyIndex, dstKeyPayload, dstKeyPayload.size, srcKeyIndex)
                    if (ret == SdkResult.Success) {
                        keyLoaded = true
                        responseCode = "00"
                        responseMessage = "Success"
                    } else if (ret == SdkResult.PinPad_KeyIdx_Error) {
                        responseCode = "02"
                        responseMessage = "WKEY Write Error [KeyIdx_Error]"
                        Logger.e(tAG, "writeMKey Error: PinPad_KeyIdx_Error")
                    } else if (ret == SdkResult.Param_In_Invalid) {
                        responseCode = "03"
                        responseMessage = "WKEY Write Error [Param_In_Invalid]"
                        Logger.e(tAG, "writeMKey Error: Param_In_Invalid")
                    } else {
                        responseCode = "04"
                        responseMessage = "WKEY Write Error [$ret]"
                        Logger.e(tAG, "writeMKey Error: [$ret]")
                    }
                }
                EPedKeyType.TIK -> {
                    val ret = ped.dukptCipherKeyInject( dstKeyIndex, srcKeyIndex, null,
                        DukptKeyTypeEnum.IPEK, CalcModeEnum.ENCRYPT, dstKeyPayload, dstKSN)
                    if (ret == SdkResult.Success) {
                        keyLoaded = true
                        responseCode = "00"
                        responseMessage = "Success"
                    } else if (ret == SdkResult.PinPad_KeyIdx_Error) {
                        responseCode = "02"
                        responseMessage = "WKEY Write Error [KeyIdx_Error]"
                        Logger.e(tAG, "writeMKey Error: PinPad_KeyIdx_Error")
                    } else if (ret == SdkResult.Param_In_Invalid) {
                        responseCode = "03"
                        responseMessage = "WKEY Write Error [Param_In_Invalid]"
                        Logger.e(tAG, "writeMKey Error: Param_In_Invalid")
                    } else {
                        responseCode = "04"
                        responseMessage = "WKEY Write Error [$ret]"
                        Logger.e(tAG, "writeMKey Error: [$ret]")
                    }
                }
                EPedKeyType.TPK -> {
                    if (srcKeyIndex != dstKeyIndex) {
                        return WriteKeyResult(false, "E7", "Nexgo TPK must use the same index as its parent master key")
                    }
                    val ret = ped.writeWKey(srcKeyIndex, WorkKeyTypeEnum.PINKEY, dstKeyPayload, dstKeyPayload.size)
                    if (ret == SdkResult.Success) {
                        keyLoaded = true
                        responseCode = "00"
                        responseMessage = "Success"
                    } else if (ret == SdkResult.PinPad_KeyIdx_Error) {
                        responseCode = "02"
                        responseMessage = "WKEY Write Error [KeyIdx_Error]"
                        Logger.e(tAG, "writeMKey Error: PinPad_KeyIdx_Error")
                    } else if (ret == SdkResult.Param_In_Invalid) {
                        responseCode = "03"
                        responseMessage = "WKEY Write Error [Param_In_Invalid]"
                        Logger.e(tAG, "writeMKey Error: Param_In_Invalid")
                    } else {
                        responseCode = "04"
                        responseMessage = "WKEY Write Error [$ret]"
                        Logger.e(tAG, "writeMKey Error: [$ret]")
                    }
                }
                EPedKeyType.TDK -> {
                    if (srcKeyIndex != dstKeyIndex) {
                        return WriteKeyResult(false, "E7", "Nexgo TDK must use the same index as its parent master key")
                    }
                    val ret = ped.writeWKey(srcKeyIndex, WorkKeyTypeEnum.ENCRYPTIONKEY, dstKeyPayload, dstKeyPayload.size)
                    if (ret == SdkResult.Success) {
                        keyLoaded = true
                        responseCode = "00"
                        responseMessage = "Success"
                    } else if (ret == SdkResult.PinPad_KeyIdx_Error) {
                        responseCode = "02"
                        responseMessage = "WKEY Write Error [KeyIdx_Error]"
                        Logger.e(tAG, "writeMKey Error: PinPad_KeyIdx_Error")
                    } else if (ret == SdkResult.Param_In_Invalid) {
                        responseCode = "03"
                        responseMessage = "WKEY Write Error [Param_In_Invalid]"
                        Logger.e(tAG, "writeMKey Error: Param_In_Invalid")
                    } else {
                        responseCode = "04"
                        responseMessage = "WKEY Write Error [$ret]"
                        Logger.e(tAG, "writeMKey Error: [$ret]")
                    }
                }
                EPedKeyType.TAK -> {
                    if (srcKeyIndex != dstKeyIndex) {
                        return WriteKeyResult(false, "E7", "Nexgo TAK must use the same index as its parent master key")
                    }
                    val ret = ped.writeWKey(srcKeyIndex, WorkKeyTypeEnum.MACKEY, dstKeyPayload, dstKeyPayload.size)
                    if (ret == SdkResult.Success) {
                        keyLoaded = true
                        responseCode = "00"
                        responseMessage = "Success"
                    } else if (ret == SdkResult.PinPad_KeyIdx_Error) {
                        responseCode = "02"
                        responseMessage = "WKEY Write Error [KeyIdx_Error]"
                        Logger.e(tAG, "writeMKey Error: PinPad_KeyIdx_Error")
                    } else if (ret == SdkResult.Param_In_Invalid) {
                        responseCode = "03"
                        responseMessage = "WKEY Write Error [Param_In_Invalid]"
                        Logger.e(tAG, "writeMKey Error: Param_In_Invalid")
                    } else {
                        responseCode = "04"
                        responseMessage = "WKEY Write Error [$ret]"
                        Logger.e(tAG, "writeMKey Error: [$ret]")
                    }
                }
                else -> {
                    responseCode = "F2"
                    responseMessage = "Error. Invalid DstKey Type [$dstKeyType]"
                    Logger.e(tAG, "Error. Invalid DstKey Type [$dstKeyType]")
                }
            }
        }
        //The key is in Clear Key Format
        else if (srcKeyType == EPedKeyType.None) { //Clear Key Injection
            when (dstKeyType) {
                EPedKeyType.TMK -> {
                    if (dstKeyIndex > 0 && dstKeyIndex < 199) {
                        val ret = ped.writeMKey(dstKeyIndex, dstKeyPayload, dstKeyPayload.size)
                        if (ret == SdkResult.Success) {
                            keyLoaded = true
                            responseCode = "00"
                            responseMessage = "Success"
                        } else if (ret == SdkResult.PinPad_KeyIdx_Error) {
                            responseCode = "02"
                            responseMessage = "MKEY Write Error [KeyIdx_Error]"
                            Logger.e(tAG, "writeMKey Error: PinPad_KeyIdx_Error")
                        } else if (ret == SdkResult.Param_In_Invalid) {
                            responseCode = "03"
                            responseMessage = "MKEY Write Error [Param_In_Invalid]"
                            Logger.e(tAG, "writeMKey Error: Param_In_Invalid")
                        } else {
                            responseCode = "04"
                            responseMessage = "MKEY Write Error [$ret]"
                            Logger.e(tAG, "writeMKey Error: [$ret]")
                        }
                    }
                    else
                    {
                        responseCode = "05"
                        responseMessage = "Invalid MKEY Index"
                        Logger.e(tAG, "writeMKey Error: Invalid Mkey Index")
                    }
                }
                EPedKeyType.TLK -> {
                    // Always load TLK in TMK Slot 0
                    val ret = ped.writeMKey(0, dstKeyPayload, dstKeyPayload.size)
                    if (ret == SdkResult.Success) {
                        keyLoaded = true
                        responseCode = "00"
                        responseMessage = "Success"
                    } else if (ret == SdkResult.PinPad_KeyIdx_Error) {
                        responseCode = "02"
                        responseMessage = "MKEY Write Error [KeyIdx_Error]"
                        Logger.e(tAG, "TLK writeMKey Error: PinPad_KeyIdx_Error")
                    } else if (ret == SdkResult.Param_In_Invalid) {
                        responseCode = "03"
                        responseMessage = "MKEY Write Error [Param_In_Invalid]"
                        Logger.e(tAG, "TLK writeMKey Error: Param_In_Invalid")
                    } else {
                        responseCode = "04"
                        responseMessage = "MKEY Write Error [$ret]"
                        Logger.e(tAG, "TLK writeMKey Error: [$ret]")
                    }
                }
                EPedKeyType.TIK -> {
                    val ret = ped.dukptKeyInject(dstKeyIndex, DukptKeyTypeEnum.IPEK, dstKeyPayload, dstKeyPayload.size, dstKSN)
                    if (ret == SdkResult.Success) {
                        keyLoaded = true
                        responseCode = "00"
                        responseMessage = "Success"
                    } else if (ret == SdkResult.PinPad_KeyIdx_Error) {
                        responseCode = "02"
                        responseMessage = "DUKPT Write Error [KeyIdx_Error]"
                        Logger.e(tAG, "dukptKeyInject Error: PinPad_KeyIdx_Error")
                    } else if (ret == SdkResult.Param_In_Invalid) {
                        responseCode = "03"
                        responseMessage = "DUKPT Write Error [Param_In_Invalid]"
                        Logger.e(tAG, "dukptKeyInject Error: Param_In_Invalid")
                    } else {
                        responseCode = "04"
                        responseMessage = "DUKPT Write Error [$ret]"
                        Logger.e(tAG, "dukptKeyInject Error: [$ret]")
                    }
                }
                EPedKeyType.TPK -> {
                    return writeClearWorkingKey(
                        ped,
                        WorkKeyTypeEnum.PINKEY,
                        dstKeyType,
                        dstKeyIndex,
                        dstKeyPayload,
                        dstKeyKCV
                    )
                }
                EPedKeyType.TDK -> {
                    responseCode = "E6"
                    responseMessage = "TDK Clear Working Key Injection Not Supported"
                    Logger.e(tAG, " TDK Clear Working Key Injection Not Supported")
                }
                EPedKeyType.TAK -> {
                    return writeClearWorkingKey(
                        ped,
                        WorkKeyTypeEnum.MACKEY,
                        dstKeyType,
                        dstKeyIndex,
                        dstKeyPayload,
                        dstKeyKCV
                    )
                }
                else -> {
                    responseCode = "FE"
                    responseMessage = "Invalid DST Key Type"
                    Logger.e(tAG, "Invalid DST Key Type: [$dstKeyType]")
                }
            }
        }
        return WriteKeyResult(keyLoaded, responseCode, responseMessage)
    }

    /**
     * The Nexgo SDK stores each working key beneath a master-key index and cannot
     * write a clear working key directly. If the destination has no master key, this
     * bridge creates a random PED-resident parent master in that slot, encrypts the
     * clear working key through the PED, then loads and verifies the working key.
     */
    private fun writeClearWorkingKey(
        ped: PinPad,
        workKeyType: WorkKeyTypeEnum,
        dstKeyType: EPedKeyType,
        dstKeyIndex: Int,
        clearKeyPayload: ByteArray,
        expectedKcv: ByteArray
    ): WriteKeyResult = synchronized(clearWorkingKeyLock) {
        if (clearKeyPayload.size != 16 && clearKeyPayload.size != 24) {
            return@synchronized WriteKeyResult(
                false,
                "E5",
                "Clear PIN/MAC key must be a 16-byte or 24-byte TDES key"
            )
        }

        if (dstKeyIndex !in 1..10) {
            return@synchronized WriteKeyResult(
                false,
                "E5",
                "Clear PIN/MAC destination index must be from 1 through 10"
            )
        }

        var generatedParentMaster: ByteArray? = null
        var encryptedWorkingKey: ByteArray? = null
        var parentMasterInitiallyPresent = false
        var parentMasterWriteAttempted = false
        var workingKeyWritten = false
        var result = WriteKeyResult(false, "E5", "Parent master-key preparation failed")

        try {
            parentMasterInitiallyPresent = ped.isKeyExist(dstKeyIndex)
            var parentReady = parentMasterInitiallyPresent
            if (!parentMasterInitiallyPresent) {
                generatedParentMaster = GeneratedParentMasterKeyMaterial.generate(clearKeyPayload.size)
                parentMasterWriteAttempted = true
                val masterWriteResult = ped.writeMKey(
                    dstKeyIndex,
                    generatedParentMaster,
                    generatedParentMaster.size
                )
                parentReady = masterWriteResult == SdkResult.Success
                if (!parentReady) {
                    Logger.e(tAG, "Generated parent writeMKey failed in slot $dstKeyIndex: [$masterWriteResult]")
                    result = WriteKeyResult(false, "E5", "Generated parent master-key load failed [$masterWriteResult]")
                }
            }

            if (parentReady) {
                encryptedWorkingKey = ped.encryptByMKey(
                    dstKeyIndex,
                    clearKeyPayload,
                    clearKeyPayload.size
                )

                if (encryptedWorkingKey?.size != clearKeyPayload.size) {
                    Logger.e(tAG, "encryptByMKey failed for parent slot $dstKeyIndex")
                    result = WriteKeyResult(false, "E6", "PED clear working-key encryption failed")
                } else {
                    val workWriteResult = ped.writeWKey(
                        dstKeyIndex,
                        workKeyType,
                        encryptedWorkingKey,
                        encryptedWorkingKey.size
                    )
                    if (workWriteResult != SdkResult.Success) {
                        Logger.e(tAG, "Generated-parent writeWKey failed for destination $dstKeyIndex: [$workWriteResult]")
                        result = WriteKeyResult(false, "E7", "Working-key write failed [$workWriteResult]")
                    } else {
                        workingKeyWritten = true
                        val actualKcv = checkKey(dstKeyType, dstKeyIndex)
                        val expectedKcvHex = expectedKcv.toHex()
                        if (actualKcv == null || !actualKcv.startsWith(expectedKcvHex, ignoreCase = true)) {
                            Logger.e(tAG, "Clear working-key KCV verification failed for destination $dstKeyIndex")
                            result = WriteKeyResult(false, "E8", "Working-key KCV verification failed")
                        } else {
                            result = WriteKeyResult(true, "00", "Success")
                        }
                    }
                }
            }
        } catch (exception: Exception) {
            Logger.e(tAG, "Clear working-key bridge failed", exception)
            result = WriteKeyResult(false, "E9", "Clear working-key bridge failed")
        } finally {
            if (!result.success && workingKeyWritten) {
                try {
                    if (!ped.deleteWKey(dstKeyIndex, workKeyType)) {
                        Logger.e(tAG, "Working-key rollback returned false for destination $dstKeyIndex")
                    }
                } catch (exception: Exception) {
                    Logger.e(tAG, "Unable to roll back working key at destination $dstKeyIndex", exception)
                }
            }

            // A generated parent must remain while its working key is usable. Only
            // remove it when this operation failed, and never remove a pre-existing TMK.
            if (!result.success && !parentMasterInitiallyPresent && parentMasterWriteAttempted) {
                val parentDeleted = try {
                    !ped.isKeyExist(dstKeyIndex) || ped.deleteMKey(dstKeyIndex)
                } catch (exception: Exception) {
                    Logger.e(tAG, "Unable to roll back generated parent master at $dstKeyIndex", exception)
                    false
                }
                if (!parentDeleted) {
                    result = WriteKeyResult(false, "EA", "Generated parent master-key rollback failed")
                }
            }

            generatedParentMaster?.fill(0)
            encryptedWorkingKey?.fill(0)
        }

        result
    }

}
