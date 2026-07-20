package one.globalconnect.pinpad.device

import com.nexgo.oaf.apiv3.DeviceEngine
import one.globalconnect.pinpad.BuildConfig

class NexgoDeviceInfoProvider(
    private val deviceEngine: DeviceEngine,
) : PinpadDeviceInfoProvider {
    override fun modelName(): String {
        return runCatching { deviceEngine.deviceInfo.model }
            .getOrDefault("")
            .ifBlank { "UNKNOWN" }
    }

    override fun serialNumber(): String {
        return runCatching { deviceEngine.deviceInfo.sn }
            .getOrDefault("")
            .ifBlank { "0000000000000000" }
            .take(16)
    }

    override fun firmwareVersion(part: Char, option: Char?): FirmwareVersion {
        val info = runCatching { deviceEngine.deviceInfo }.getOrNull()
        val version = when {
            part == '6' && option != null -> optionKernelVersion(option, info?.kernelVer)
            part == '0' -> info?.firmWareFullVersion ?: info?.firmWareVer
            part == '1' -> info?.spCoreVersion
            part == '2' -> info?.spBootVersion
            part == '3' -> info?.kernelVer
            part == '4' -> BuildConfig.VERSION_NAME
            else -> info?.firmWareVer
        }.orEmpty().ifBlank { "CT20P-0.1.0" }

        return FirmwareVersion(version = version.take(MAX_VERSION_LENGTH))
    }

    override fun hardwareCapabilities(): List<String> = listOf("ICC", "MSR", "PCD")

    private fun optionKernelVersion(option: Char, fallback: String?): String {
        return when (option) {
            'A' -> "CL-L1"
            'B' -> "CT-L1"
            'C' -> fallback ?: "CT-L2"
            else -> fallback ?: "KERNEL"
        }
    }

    companion object {
        private const val MAX_VERSION_LENGTH = 48
    }
}
