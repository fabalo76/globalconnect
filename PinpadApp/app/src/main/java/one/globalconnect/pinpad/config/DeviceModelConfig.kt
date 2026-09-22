package one.globalconnect.pinpad.config

data class DeviceModelSpec(
    val modelName: String,
    val usbCdcSupported: Boolean,
    val rs232SerialSupported: Boolean,
    val hasPhysicalKeypad: Boolean = false,
    val defaultSerialPort: Int,
    val usbCdcSerialPort: Int? = null,
    val alternateSerialPort: Int? = null,
    val display: DisplaySpec? = null,
    val notes: String = "",
    val usbBaseSerialSupported: Boolean = false,
    val fixedUsbBasePort: Int? = null,
)

data class DisplaySpec(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int? = null,
)

object DeviceModelConfig {
    private val deviceModels = listOf(
        DeviceModelSpec(
            modelName = "N6ProLite",
            usbCdcSupported = false,
            usbBaseSerialSupported = true,
            fixedUsbBasePort = 0,
            rs232SerialSupported = true,
            defaultSerialPort = 1,
            alternateSerialPort = 0,
            display = DisplaySpec(720, 1440, 320),
            notes = "PL2303GC USB base uses fixed serial port 0, confirmed on device.",
        ),
        DeviceModelSpec(
            modelName = "UN20",
            usbCdcSupported = true,
            rs232SerialSupported = true,
            defaultSerialPort = 1,
            alternateSerialPort = 2,
            notes = "Serial port 1 or 2 depending on physical port insertion",
        ),
        DeviceModelSpec(
            modelName = "N86",
            usbCdcSupported = true,
            rs232SerialSupported = true,
            defaultSerialPort = 101,
            alternateSerialPort = 0,
            notes = "Port 101 when docked with base station, port 0 when standalone",
        ),
        DeviceModelSpec(
            modelName = "N82",
            usbCdcSupported = true,
            rs232SerialSupported = true,
            defaultSerialPort = 0,
            display = DisplaySpec(widthPx = 480, heightPx = 854, densityDpi = 244),
            notes = "Display spec copied from POS_Mobile previews",
        ),
        DeviceModelSpec(
            modelName = "N6",
            usbCdcSupported = true,
            rs232SerialSupported = true,
            defaultSerialPort = 1,
            notes = "Fixed RS232 serial port",
        ),
        DeviceModelSpec(
            modelName = "N96",
            usbCdcSupported = true,
            rs232SerialSupported = true,
            defaultSerialPort = 0,
            display = DisplaySpec(widthPx = 720, heightPx = 1600, densityDpi = 320),
            notes = "Display spec copied from POS_Mobile previews",
        ),
        DeviceModelSpec(
            modelName = "N6PRO",
            usbCdcSupported = false,
            usbBaseSerialSupported = true,
            fixedUsbBasePort = 0,
            rs232SerialSupported = true,
            defaultSerialPort = 1,
            notes = "Fixed RS232 serial port",
        ),
        DeviceModelSpec(
            modelName = "CT20P",
            usbCdcSupported = true,
            rs232SerialSupported = true,
            hasPhysicalKeypad = true,
            defaultSerialPort = 1,
            usbCdcSerialPort = 0,
            display = DisplaySpec(widthPx = 480, heightPx = 800),
            notes = "RS232 uses port 1; built-in USB CDC uses port 0",
        ),
    ).associateBy { it.modelName.normalizedModelName() }

    private val defaultSpec = DeviceModelSpec(
        modelName = "DEFAULT",
        usbCdcSupported = true,
        rs232SerialSupported = true,
        defaultSerialPort = 0,
        notes = "Fallback configuration for unrecognized models",
    )

    fun getDeviceSpec(modelName: String?): DeviceModelSpec {
        return deviceModels[modelName.normalizedModelName()] ?: defaultSpec
    }

    fun getSerialPort(modelName: String?, useAlternate: Boolean = false): Int {
        val spec = getDeviceSpec(modelName)
        return if (useAlternate) {
            spec.alternateSerialPort ?: spec.defaultSerialPort
        } else {
            spec.defaultSerialPort
        }
    }

    fun getUsbCdcSerialPort(modelName: String?): Int {
        val spec = getDeviceSpec(modelName)
        return spec.usbCdcSerialPort ?: spec.defaultSerialPort
    }

    fun supportsUsbCdc(modelName: String?): Boolean = getDeviceSpec(modelName).usbCdcSupported

    fun supportsRs232Serial(modelName: String?): Boolean = getDeviceSpec(modelName).rs232SerialSupported

    /** CT20 and CT20P terminals have a built-in physical keypad. */
    fun hasPhysicalKeypad(modelName: String?): Boolean {
        val normalized = modelName.normalizedModelName()
        return normalized.startsWith("CT20") || getDeviceSpec(modelName).hasPhysicalKeypad
    }

    fun getAllModels(): List<DeviceModelSpec> = deviceModels.values.toList()

    private fun String?.normalizedModelName(): String {
        return orEmpty()
            .uppercase()
            .filter { it.isLetterOrDigit() }
    }
}
