package one.globalconnect.pinpad.storage

import android.content.Context
import one.globalconnect.pinpad.config.DeviceModelConfig

class PinpadPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun idleMessage(): String = prefs.getString(KEY_IDLE_MESSAGE, "").orEmpty()

    fun setIdleMessage(value: String) {
        prefs.edit().putString(KEY_IDLE_MESSAGE, value.take(MAX_IDLE_MESSAGE_LENGTH)).apply()
    }

    fun promptLanguageIndex(): String = prefs.getString(KEY_PROMPT_LANGUAGE_INDEX, DEFAULT_PROMPT_LANGUAGE_INDEX).orEmpty()

    fun setPromptLanguageIndex(value: String) {
        prefs.edit().putString(KEY_PROMPT_LANGUAGE_INDEX, value.take(1)).apply()
    }

    fun displayFontSize(): String = prefs.getString(KEY_DISPLAY_FONT_SIZE, DEFAULT_DISPLAY_FONT_SIZE).orEmpty()

    fun setDisplayFontSize(value: String) {
        prefs.edit().putString(KEY_DISPLAY_FONT_SIZE, value.take(1)).apply()
    }

    fun displayFontColor(): DisplayFontColor {
        return DisplayFontColor(
            foreground = prefs.getString(KEY_DISPLAY_FONT_FOREGROUND, DEFAULT_DISPLAY_FOREGROUND).orEmpty(),
            background = prefs.getString(KEY_DISPLAY_FONT_BACKGROUND, DEFAULT_DISPLAY_BACKGROUND).orEmpty(),
        )
    }

    fun setDisplayFontColor(foreground: String, background: String) {
        prefs.edit()
            .putString(KEY_DISPLAY_FONT_FOREGROUND, foreground.take(COLOR_LENGTH))
            .putString(KEY_DISPLAY_FONT_BACKGROUND, background.take(COLOR_LENGTH))
            .apply()
    }

    fun serialSettings(modelName: String? = null): SerialSettings {
        val modelDefaultPort = DeviceModelConfig.getSerialPort(modelName)
        return SerialSettings(
            transportMode = prefs.getString(KEY_TRANSPORT_MODE, null) ?: DEFAULT_TRANSPORT_MODE,
            rs232Port = if (prefs.contains(KEY_RS232_PORT)) {
                prefs.getInt(KEY_RS232_PORT, modelDefaultPort)
            } else {
                modelDefaultPort
            },
            usbVid = prefs.getInt(KEY_USB_VID, DEFAULT_USB_VID),
            usbPid = prefs.getInt(KEY_USB_PID, DEFAULT_USB_PID),
            baudRate = prefs.getInt(KEY_BAUD_RATE, DEFAULT_BAUD_RATE),
            dataBits = prefs.getInt(KEY_DATA_BITS, DEFAULT_DATA_BITS),
            stopBits = prefs.getInt(KEY_STOP_BITS, DEFAULT_STOP_BITS),
            parity = prefs.getString(KEY_PARITY, null) ?: DEFAULT_PARITY,
        )
    }

    fun setSerialSettings(settings: SerialSettings) {
        prefs.edit()
            .putString(KEY_TRANSPORT_MODE, settings.transportMode)
            .putInt(KEY_RS232_PORT, settings.rs232Port)
            .putInt(KEY_USB_VID, settings.usbVid)
            .putInt(KEY_USB_PID, settings.usbPid)
            .putInt(KEY_BAUD_RATE, settings.baudRate)
            .putInt(KEY_DATA_BITS, settings.dataBits)
            .putInt(KEY_STOP_BITS, settings.stopBits)
            .putString(KEY_PARITY, settings.parity)
            .apply()
    }

    fun activeMasterKeyId(): String = prefs.getString(KEY_ACTIVE_MASTER_KEY, "0").orEmpty()

    fun setActiveMasterKeyId(keyId: String) {
        prefs.edit().putString(KEY_ACTIVE_MASTER_KEY, keyId.take(1)).apply()
    }

    fun masterKeyAttribute(keyId: Char): String? {
        return prefs.getString(masterKeyAttributeKey(keyId), null)?.takeIf { it.isNotBlank() }
    }

    fun setMasterKeyAttribute(keyId: Char, value: String) {
        prefs.edit().putString(masterKeyAttributeKey(keyId), value).apply()
    }

    fun masterKeyKcv(keyId: Char): String? {
        return prefs.getString(masterKeyKcvKey(keyId), null)?.takeIf { it.isNotBlank() }
    }

    fun setMasterKeyKcv(keyId: Char, value: String) {
        prefs.edit().putString(masterKeyKcvKey(keyId), value.take(6)).apply()
    }

    fun activeDukptKeySet(): Int = prefs.getInt(KEY_ACTIVE_DUKPT_KEY_SET, DEFAULT_DUKPT_KEY_SET).coerceIn(0, 1)

    fun setActiveDukptKeySet(keySet: Int) {
        prefs.edit().putInt(KEY_ACTIVE_DUKPT_KEY_SET, keySet.coerceIn(0, 1)).apply()
    }

    fun dukptFullKsnOutput(): Boolean = prefs.getBoolean(KEY_DUKPT_FULL_KSN_OUTPUT, DEFAULT_DUKPT_FULL_KSN_OUTPUT)

    fun setDukptFullKsnOutput(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DUKPT_FULL_KSN_OUTPUT, enabled).apply()
    }

    fun keypadBeeperEnabled(): Boolean = prefs.getBoolean(KEY_KEYPAD_BEEPER_ENABLED, true)

    fun setKeypadBeeperEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEYPAD_BEEPER_ENABLED, enabled).apply()
    }

    fun msrTrackMode(): String = prefs.getString(KEY_MSR_TRACK_MODE, DEFAULT_MSR_TRACK_MODE).orEmpty()

    fun setMsrTrackMode(mode: String) {
        prefs.edit().putString(KEY_MSR_TRACK_MODE, mode.take(1)).apply()
    }

    fun msrRetryCount(): Int = prefs.getInt(KEY_MSR_RETRY_COUNT, DEFAULT_MSR_RETRY_COUNT)

    fun setMsrRetryCount(count: Int) {
        prefs.edit().putInt(KEY_MSR_RETRY_COUNT, count.coerceIn(0, 9)).apply()
    }

    fun msrOutputFormat(): String = prefs.getString(KEY_MSR_OUTPUT_FORMAT, DEFAULT_MSR_OUTPUT_FORMAT).orEmpty()

    fun setMsrOutputFormat(format: String) {
        prefs.edit().putString(KEY_MSR_OUTPUT_FORMAT, format.take(1)).apply()
    }

    fun msrAutoArmEnabled(): Boolean = prefs.getBoolean(KEY_MSR_AUTO_ARM, DEFAULT_MSR_AUTO_ARM)

    fun setMsrAutoArmEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MSR_AUTO_ARM, enabled).apply()
    }

    fun pusn(slot: Int): String = prefs.getString(pusnKey(slot), "").orEmpty()

    fun setPusn(slot: Int, pusn: String) {
        prefs.edit().putString(pusnKey(slot), pusn.take(PUSN_LENGTH)).apply()
    }

    fun mifareKey(slot: Int): MifareKeySlot? {
        if (slot !in MIFARE_KEY_SLOT_RANGE) return null
        val value = prefs.getString(mifareKeySlotKey(slot), null)?.takeIf { it.length == MIFARE_KEY_PAIR_LENGTH }
            ?: return null
        return MifareKeySlot(
            keyA = value.take(MIFARE_KEY_LENGTH),
            keyB = value.drop(MIFARE_KEY_LENGTH).take(MIFARE_KEY_LENGTH),
        )
    }

    fun setMifareKey(slot: Int, keyA: String, keyB: String) {
        if (slot !in MIFARE_KEY_SLOT_RANGE) return
        prefs.edit()
            .putString(mifareKeySlotKey(slot), keyA.take(MIFARE_KEY_LENGTH) + keyB.take(MIFARE_KEY_LENGTH))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "pinpad_runtime"
        private const val KEY_IDLE_MESSAGE = "idle_message"
        private const val KEY_PROMPT_LANGUAGE_INDEX = "prompt_language_index"
        private const val KEY_DISPLAY_FONT_SIZE = "display_font_size"
        private const val KEY_DISPLAY_FONT_FOREGROUND = "display_font_foreground"
        private const val KEY_DISPLAY_FONT_BACKGROUND = "display_font_background"
        private const val KEY_TRANSPORT_MODE = "transport_mode"
        private const val KEY_RS232_PORT = "rs232_port"
        private const val KEY_USB_VID = "usb_vid"
        private const val KEY_USB_PID = "usb_pid"
        private const val KEY_BAUD_RATE = "baud_rate"
        private const val KEY_DATA_BITS = "data_bits"
        private const val KEY_STOP_BITS = "stop_bits"
        private const val KEY_PARITY = "parity"
        private const val KEY_ACTIVE_MASTER_KEY = "active_master_key"
        private const val KEY_MASTER_KEY_ATTRIBUTE_PREFIX = "master_key_attribute_"
        private const val KEY_MASTER_KEY_KCV_PREFIX = "master_key_kcv_"
        private const val KEY_ACTIVE_DUKPT_KEY_SET = "active_dukpt_key_set"
        private const val KEY_DUKPT_FULL_KSN_OUTPUT = "dukpt_full_ksn_output"
        private const val KEY_KEYPAD_BEEPER_ENABLED = "keypad_beeper_enabled"
        private const val KEY_MSR_TRACK_MODE = "msr_track_mode"
        private const val KEY_MSR_RETRY_COUNT = "msr_retry_count"
        private const val KEY_MSR_OUTPUT_FORMAT = "msr_output_format"
        private const val KEY_MSR_AUTO_ARM = "msr_auto_arm"
        private const val KEY_PUSN_PREFIX = "pusn_slot_"
        private const val KEY_MIFARE_KEY_SLOT_PREFIX = "mifare_key_slot_"
        private const val MAX_IDLE_MESSAGE_LENGTH = 64
        private const val COLOR_LENGTH = 6
        private const val PUSN_LENGTH = 11
        private const val MIFARE_KEY_LENGTH = 12
        private const val MIFARE_KEY_PAIR_LENGTH = MIFARE_KEY_LENGTH * 2
        private val MIFARE_KEY_SLOT_RANGE = 0..255

        private const val DEFAULT_TRANSPORT_MODE = "AUTO"
        private const val DEFAULT_USB_VID = 0x6352
        private const val DEFAULT_USB_PID = 0x294A
        private const val DEFAULT_BAUD_RATE = 9600
        private const val DEFAULT_DATA_BITS = 8
        private const val DEFAULT_STOP_BITS = 1
        private const val DEFAULT_PARITY = "N"
        private const val DEFAULT_PROMPT_LANGUAGE_INDEX = "0"
        private const val DEFAULT_DISPLAY_FONT_SIZE = "2"
        private const val DEFAULT_DISPLAY_FOREGROUND = "FFFFFF"
        private const val DEFAULT_DISPLAY_BACKGROUND = "000000"
        private const val DEFAULT_MSR_TRACK_MODE = "1"
        private const val DEFAULT_MSR_RETRY_COUNT = 0
        private const val DEFAULT_MSR_OUTPUT_FORMAT = "0"
        private const val DEFAULT_MSR_AUTO_ARM = false
        private const val DEFAULT_DUKPT_KEY_SET = 0
        private const val DEFAULT_DUKPT_FULL_KSN_OUTPUT = false

        private fun pusnKey(slot: Int): String = "$KEY_PUSN_PREFIX$slot"

        private fun mifareKeySlotKey(slot: Int): String = "$KEY_MIFARE_KEY_SLOT_PREFIX$slot"

        private fun masterKeyAttributeKey(keyId: Char): String {
            return "$KEY_MASTER_KEY_ATTRIBUTE_PREFIX${keyId.uppercaseChar()}"
        }

        private fun masterKeyKcvKey(keyId: Char): String {
            return "$KEY_MASTER_KEY_KCV_PREFIX${keyId.uppercaseChar()}"
        }
    }
}

data class DisplayFontColor(
    val foreground: String,
    val background: String,
)

data class SerialSettings(
    val transportMode: String,
    val rs232Port: Int,
    val usbVid: Int,
    val usbPid: Int,
    val baudRate: Int,
    val dataBits: Int,
    val stopBits: Int,
    val parity: String,
)

data class MifareKeySlot(
    val keyA: String,
    val keyB: String,
)
