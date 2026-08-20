package one.globalconnect.keyinjection.config

/**
 * Unified Device Model Configuration
 *
 * This configuration table manages serial port settings for different POS/Pinpad device models.
 * It provides a centralized way to add support for new models and manage their capabilities.
 */
data class DeviceModelSpec(
    val modelName: String,
    val usbCdcSupported: Boolean,
    val rs232SerialSupported: Boolean,
    val defaultSerialPort: Int,
    val alternateSerialPort: Int? = null,
    val notes: String = ""
)

object DeviceModelConfig {

    /**
     * Unified Device Model Table
     *
     * | Model  | USB-CDC | RS232/Serial | Default Port | Alt Port | Notes                          |
     * |--------|---------|--------------|--------------|----------|--------------------------------|
     * | UN20   | Yes     | Yes          | 1            | 2        | Port depends on insertion      |
     * | N86    | Yes     | Yes          | 101          | 0        | 101 when docked, 0 standalone  |
     * | CT20P  | Yes     | Yes          | 1            | -        | Fixed RS232 port               |
     * | Default| Yes     | Yes          | 0            | -        | Fallback for unknown models    |
     */
    private val deviceModels = mapOf(
        "UN20" to DeviceModelSpec(
            modelName = "UN20",
            usbCdcSupported = true,
            rs232SerialSupported = true,
            defaultSerialPort = 1,
            alternateSerialPort = 2,
            notes = "Serial port 1 or 2 depending on physical port insertion"
        ),

        "N86" to DeviceModelSpec(
            modelName = "N86",
            usbCdcSupported = true,
            rs232SerialSupported = true,
            defaultSerialPort = 101,
            alternateSerialPort = 0,
            notes = "Port 101 when docked with base station, port 0 when standalone"
        ),

        "CT20P" to DeviceModelSpec(
            modelName = "CT20P",
            usbCdcSupported = false,
            rs232SerialSupported = true,
            defaultSerialPort = 1,
            alternateSerialPort = null,
            notes = "Fixed RS232 serial port"
        )
    )

    /**
     * Default fallback configuration for unknown device models
     */
    private val defaultSpec = DeviceModelSpec(
        modelName = "DEFAULT",
        usbCdcSupported = true,
        rs232SerialSupported = true,
        defaultSerialPort = 0,
        alternateSerialPort = null,
        notes = "Fallback configuration for unrecognized models"
    )

    /**
     * Get device specification by model name (case-insensitive)
     */
    fun getDeviceSpec(modelName: String): DeviceModelSpec {
        return deviceModels[modelName.uppercase()] ?: defaultSpec
    }

    /**
     * Get serial port number for a specific model
     */
    fun getSerialPort(modelName: String, useAlternate: Boolean = false): Int {
        val spec = getDeviceSpec(modelName)
        return if (useAlternate && spec.alternateSerialPort != null) {
            spec.alternateSerialPort
        } else {
            spec.defaultSerialPort
        }
    }

    /**
     * Check if a model supports USB-CDC
     */
    fun supportsUsbCdc(modelName: String): Boolean {
        return getDeviceSpec(modelName).usbCdcSupported
    }

    /**
     * Check if a model supports RS232/Serial
     */
    fun supportsRs232Serial(modelName: String): Boolean {
        return getDeviceSpec(modelName).rs232SerialSupported
    }

    /**
     * Get all registered device models
     */
    fun getAllModels(): List<DeviceModelSpec> {
        return deviceModels.values.toList()
    }

    /**
     * Add a new device model configuration (for runtime extension)
     */
    fun registerModel(spec: DeviceModelSpec) {
        (deviceModels as MutableMap)[spec.modelName.uppercase()] = spec
    }

    /**
     * Generate a formatted table string for display/logging
     */
    fun getFormattedTable(): String {
        val header = """
            ┌─────────┬─────────┬──────────────┬──────────────┬──────────┬────────────────────────────────┐
            │ Model   │ USB-CDC │ RS232/Serial │ Default Port │ Alt Port │ Notes                          │
            ├─────────┼─────────┼──────────────┼──────────────┼──────────┼────────────────────────────────┤
        """.trimIndent()

        val rows = getAllModels().joinToString("\n") { spec ->
            val usbCdc = if (spec.usbCdcSupported) "✓ Yes" else "✗ No"
            val rs232 = if (spec.rs232SerialSupported) "✓ Yes" else "✗ No"
            val altPort = spec.alternateSerialPort?.toString() ?: "-"
            val notes = spec.notes.take(30).padEnd(30)

            "│ %-7s │ %-7s │ %-12s │ %-12d │ %-8s │ %-30s │".format(
                spec.modelName, usbCdc, rs232, spec.defaultSerialPort, altPort, notes
            )
        }

        val footer = """
            └─────────┴─────────┴──────────────┴──────────────┴──────────┴────────────────────────────────┘
        """.trimIndent()

        return "$header\n$rows\n$footer"
    }
}

/**
 * Serial port configuration constants
 */
object SerialPortConfig {
    const val BAUD_RATE = 9600
    const val DATA_BITS = 8
    const val PARITY = 'n'
    const val STOP_BITS = 1

    /**
     * Standard serial configuration that applies to all models
     */
    data class StandardConfig(
        val baudRate: Int = BAUD_RATE,
        val dataBits: Int = DATA_BITS,
        val parity: Char = PARITY,
        val stopBits: Int = STOP_BITS
    )
}

/**
 * USB Serial adapter types supported
 */
enum class UsbSerialAdapter(val vendorId: Int, val productId: Int) {
    FTDI(0x0403, 0x6001),
    PL2303(0x067B, 0x2303);

    companion object {
        fun fromVendorProduct(vendorId: Int, productId: Int): UsbSerialAdapter? {
            return values().find { it.vendorId == vendorId && it.productId == productId }
        }
    }
}
