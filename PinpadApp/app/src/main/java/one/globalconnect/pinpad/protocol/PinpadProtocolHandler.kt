package one.globalconnect.pinpad.protocol

interface PinpadProtocolHandler {
    fun onBytesReceived(bytes: ByteArray): List<ByteArray>

    fun cancelActiveOperation() = Unit

    fun onKeypadKey(key: PinpadKeypadKey) = Unit

    fun shutdown() = Unit
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
