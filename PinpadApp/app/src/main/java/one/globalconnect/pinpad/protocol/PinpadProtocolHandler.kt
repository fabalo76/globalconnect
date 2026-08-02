package one.globalconnect.pinpad.protocol

interface PinpadProtocolHandler {
    fun onBytesReceived(bytes: ByteArray): List<ByteArray>

    fun consumeCompletedSerialPortChange(): SerialPortChange? = null

    fun cancelActiveOperation() = Unit

    fun onKeypadKey(key: PinpadKeypadKey) = Unit

    fun beginClearKeyInjectionMode() = Unit

    fun endClearKeyInjectionMode(reason: String) = Unit

    fun shutdown() = Unit
}

data class SerialPortChange(
    val baudRate: Int,
    val dataBits: Int,
    val stopBits: Int,
    val parity: String,
) {
    companion object {
        fun fromCommand(baudCode: Char?, mode: Char?): SerialPortChange? {
            val baudRate = when (baudCode) {
                '1' -> 1_200
                '2' -> 2_400
                '3' -> 4_800
                '4' -> 9_600
                '5' -> 19_200
                '6' -> 38_400
                '7' -> 57_600
                '8' -> 115_200
                else -> return null
            }
            val lineSettings = when (mode ?: '1') {
                '1' -> LineSettings(dataBits = 8, parity = "N")
                '2' -> LineSettings(dataBits = 7, parity = "E")
                '3' -> LineSettings(dataBits = 7, parity = "O")
                else -> return null
            }
            return SerialPortChange(
                baudRate = baudRate,
                dataBits = lineSettings.dataBits,
                stopBits = 1,
                parity = lineSettings.parity,
            )
        }
    }

    private data class LineSettings(
        val dataBits: Int,
        val parity: String,
    )
}

enum class PinpadKeypadKey {
    Digit0,
    Digit1,
    Digit2,
    Digit3,
    Digit4,
    Digit5,
    Digit6,
    Digit7,
    Digit8,
    Digit9,
    Enter,
    Clear,
    Cancel,
    Function1,
    Function2,
    Function3,
    Period,
    Space,
}
