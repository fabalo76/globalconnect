package one.globalconnect.paymentapp.uicpos.pos.host

import android.util.Log
import one.globalconnect.paymentapp.uicpos.pos.model.TransLog

/**
 * Recreates the field 22 value using the same rules applied by the legacy
 * terminal implementation.
 */
object EntryModeMapper {

    /**
     * Builds ISO 8583 field 22 from the card capture method and terminal PIN capability.
     *
     * The final digit reports capability, not whether this transaction contains a PIN block.
     *
     * @param transLog transaction data containing the card capture method.
     * @param onlinePinCap whether terminal-level online PIN capability is enabled.
     * @return the four-character POS entry mode used by the ISSwitch host protocol.
     */
    fun from(transLog: TransLog, onlinePinCap: Boolean): String {
        val entryMode = CharArray(4) { '0' }
        entryMode[3] = if (onlinePinCap) '1' else '2'
        val source = (transLog.CardDataSource.ifBlank { transLog.TxnInterface ?: "" })
            .trim()
            .uppercase()

        Log.d(TAG, "Deriving entry mode from CardDataSource='${transLog.CardDataSource}' TxnInterface='${transLog.TxnInterface}' resolvedSource='$source'")

        when {
            "CTLS" in source || "CONTACTLESS" in source -> {
                entryMode[1] = '0'
                entryMode[2] = '7'
            }
            "CHIP" in source || "ICC" in source -> {
                entryMode[1] = '0'
                entryMode[2] = '5'
            }
            "SWIPE" in source || "MAG" in source || source == "02" -> {
                entryMode[1] = '0'
                entryMode[2] = '2'
            }
            "MANUAL" in source || source == "01" -> {
                entryMode[1] = '0'
                entryMode[2] = '1'
            }
            else -> {
                entryMode[1] = '0'
                entryMode[2] = '0'
            }
        }

        val value = String(entryMode)
        Log.d(TAG, "Mapped entry mode to '$value'")
        return value
    }

    private const val TAG = "EntryModeMapper"
}
