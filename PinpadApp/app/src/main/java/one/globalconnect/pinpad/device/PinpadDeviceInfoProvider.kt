package one.globalconnect.pinpad.device

interface PinpadDeviceInfoProvider {
    fun modelName(): String
    fun serialNumber(): String
    fun firmwareVersion(part: Char, option: Char?): FirmwareVersion
    fun hardwareCapabilities(): List<String>
}

data class FirmwareVersion(
    val version: String,
    val subVersion: String = "00",
    val hash: String = DEFAULT_HASH,
) {
    companion object {
        const val DEFAULT_HASH = "0000000000000000000000000000000000000000"
    }
}
