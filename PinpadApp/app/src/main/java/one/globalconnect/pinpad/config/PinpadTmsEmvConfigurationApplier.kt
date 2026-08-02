package one.globalconnect.pinpad.config

import android.content.Context
import one.globalconnect.pinpad.PinpadApplication
import one.globalconnect.pinpad.device.PinpadContactEmvController
import one.globalconnect.pinpad.device.PinpadEmvConfigStore
import one.globalconnect.pinpad.device.PinpadEmvDataObjects
import one.globalconnect.pinpad.logging.PinpadTraceLog

data class TmsEmbeddedTextFile(
    val name: String,
    val contents: String,
    val sha256: String,
)

data class TmsConfigurationValue<T>(
    val supplied: Boolean,
    val value: T,
)

data class PinpadTmsEmvConfiguration(
    val dataFormats: TmsConfigurationValue<List<TmsEmbeddedTextFile>>,
    val terminal: TmsConfigurationValue<List<TmsEmbeddedTextFile>>,
    val caKeys: TmsConfigurationValue<List<TmsEmbeddedTextFile>>,
    val contact: TmsConfigurationValue<List<TmsEmbeddedTextFile>>,
    val contactless: TmsConfigurationValue<List<TmsEmbeddedTextFile>>,
)

class PinpadTmsEmvConfigurationApplier(private val context: Context) {
    fun apply(configuration: PinpadTmsEmvConfiguration): ApplyResult {
        val parsedDataFormats = configuration.dataFormats.takeIf { it.supplied }?.let { supplied ->
            val file = supplied.value.singleOrNull()
                ?: if (supplied.value.isEmpty()) null else error("Data Formats accepts one file")
            file?.let { PinpadEmvDataObjects.parseDataFormatText(it.contents) }
                ?: if (file == null) emptyMap() else error("Invalid Data Formats file ${file.name}")
        }
        val parsedTerminal = configuration.terminal.takeIf { it.supplied }?.let { supplied ->
            val file = supplied.value.singleOrNull()
                ?: if (supplied.value.isEmpty()) null else error("EMV Terminal configuration accepts one file")
            file?.let { PinpadEmvDataObjects.parseConfigurationText(it.contents) }
                ?: if (file == null) "" else error("Invalid EMV Terminal configuration ${file.name}")
        }
        val parsedCaKeys = configuration.caKeys.takeIf { it.supplied }?.let {
            parseUnique(it.value, "CAPK") { file ->
                val parsed = PinpadEmvDataObjects.parseCapkText(file.contents)
                    ?: error("Invalid EMV CA Key file ${file.name}")
                if (!parsed.suppliedHash.equals(parsed.computedHash, ignoreCase = true)) {
                    PinpadTraceLog.device(
                        "TMS CAPK checksum corrected file=${file.name} id=${parsed.id} " +
                            "supplied=${parsed.suppliedHash} computed=${parsed.computedHash}",
                    )
                }
                parsed.id to parsed.tlvHex
            }
        }
        val parsedContact = configuration.contact.takeIf { it.supplied }?.let {
            parseUnique(it.value, "contact AID") { file ->
                val parsed = PinpadEmvDataObjects.parseApplicationConfigurationText(file.contents)
                    ?: error("Invalid EMV Contact configuration ${file.name}")
                parsed.aid to parsed.tlvHex
            }
        }
        val parsedContactless = configuration.contactless.takeIf { it.supplied }?.let {
            parseUnique(it.value, "contactless AID") { file ->
                val parsed = PinpadEmvDataObjects.parseApplicationConfigurationText(file.contents)
                    ?: error("Invalid EMV Contactless configuration ${file.name}")
                parsed.aid to parsed.tlvHex
            }
        }

        val store = PinpadEmvConfigStore(context)
        parsedDataFormats?.let(store::setDataFormatDefinitions)
        parsedTerminal?.let(store::setTerminalConfigTlv)
        parsedCaKeys?.let(store::replaceCapkTlvs)
        parsedContact?.let(store::replaceAidTlvs)
        parsedContactless?.let(store::replacePcdAidTlvs)

        val hasEmvChanges = listOf(
            configuration.dataFormats.supplied,
            configuration.terminal.supplied,
            configuration.caKeys.supplied,
            configuration.contact.supplied,
            configuration.contactless.supplied,
        ).any { it }
        val sdkApplied = if (hasEmvChanges) {
            val application = context.applicationContext as PinpadApplication
            PinpadContactEmvController(context, application.deviceEngine).applyStoredConfiguration()
        } else {
            true
        }

        val result = ApplyResult(
            sdkApplied = sdkApplied,
            dataFormats = parsedDataFormats?.size,
            terminalUpdated = parsedTerminal != null,
            caKeys = parsedCaKeys?.size,
            contactAids = parsedContact?.size,
            contactlessAids = parsedContactless?.size,
        )
        PinpadTraceLog.device(
            "PINPAD_APP EMV configuration applied sdk=${result.sdkApplied} " +
                "dataFormats=${result.dataFormats ?: "unchanged"} terminal=${result.terminalUpdated} " +
                "capks=${result.caKeys ?: "unchanged"} contact=${result.contactAids ?: "unchanged"} " +
                "contactless=${result.contactlessAids ?: "unchanged"}",
        )
        check(sdkApplied) { "NEXGO EMV SDK rejected the downloaded configuration" }
        return result
    }

    private fun parseUnique(
        files: List<TmsEmbeddedTextFile>,
        label: String,
        parser: (TmsEmbeddedTextFile) -> Pair<String, String>,
    ): Map<String, String> {
        val parsed = linkedMapOf<String, String>()
        files.forEach { file ->
            val (id, value) = parser(file)
            check(parsed.put(id.uppercase(), value) == null) {
                "Duplicate $label $id in downloaded configuration"
            }
        }
        return parsed
    }

    data class ApplyResult(
        val sdkApplied: Boolean,
        val dataFormats: Int?,
        val terminalUpdated: Boolean,
        val caKeys: Int?,
        val contactAids: Int?,
        val contactlessAids: Int?,
    )
}
