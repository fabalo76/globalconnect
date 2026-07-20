package one.globalconnect.pinpad.device

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale

class PinpadEmvConfigStore(context: Context) {
    private val configDir = File(context.filesDir, CONFIG_DIR_NAME).apply { mkdirs() }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun terminalConfigTlv(): String {
        val stored = readTextFile(TERMINAL_CONFIG_FILE) ?: prefs.getString(KEY_TERMINAL_CONFIG_TLV, "").orEmpty()
        val kernelOnly = kernelTerminalConfigTlv(stored) ?: return stored
        if (kernelOnly != stored && kernelOnly.isNotBlank()) {
            writeTextFile(TERMINAL_CONFIG_FILE, kernelOnly)
            prefs.edit().putString(KEY_TERMINAL_CONFIG_TLV, kernelOnly).apply()
        }
        val privateTags = privateTerminalTags(stored)
        if (privateTags.isNotEmpty()) {
            writeMapFile(TERMINAL_PRIVATE_TAGS_FILE, terminalPrivateTags() + privateTags)
        }
        return kernelOnly
    }

    fun setTerminalConfigTlv(value: String) {
        val kernelOnly = kernelTerminalConfigTlv(value) ?: value
        writeTextFile(TERMINAL_CONFIG_FILE, kernelOnly)
        prefs.edit().putString(KEY_TERMINAL_CONFIG_TLV, kernelOnly).apply()
        val privateTags = privateTerminalTags(value)
        if (privateTags.isNotEmpty()) {
            writeMapFile(TERMINAL_PRIVATE_TAGS_FILE, terminalPrivateTags() + privateTags)
        }
    }

    fun dataFormats(): Map<String, String> = dataFormatDefinitions().mapValues { (_, definition) -> definition.rule }

    fun dataFormatDefinitions(): Map<String, PinpadEmvDataObjects.EmvDataFormatDefinition> {
        return readDataFormatDefinitionsFile(DATA_FORMATS_FILE)
            ?: readMap(KEY_DATA_FORMATS).mapNotNull { (tag, rule) ->
                PinpadEmvDataObjects.parseDataFormatDefinition(tag, rule)?.let { it.tag to it }
            }.toMap()
    }

    fun dataFormatDefinition(tag: String): PinpadEmvDataObjects.EmvDataFormatDefinition? {
        return dataFormatDefinitions()[tag.uppercase(Locale.US)]
    }

    fun setDataFormats(value: Map<String, String>) {
        val definitions = value.mapNotNull { (tag, rule) ->
            PinpadEmvDataObjects.parseDataFormatDefinition(tag, rule)?.let { it.tag to it }
        }.toMap()
        setDataFormatDefinitions(definitions)
    }

    fun setDataFormatDefinitions(value: Map<String, PinpadEmvDataObjects.EmvDataFormatDefinition>) {
        writeDataFormatDefinitionsFile(DATA_FORMATS_FILE, value)
        writeMap(KEY_DATA_FORMATS, value.mapValues { (_, definition) -> definition.rule })
    }

    fun terminalAssignedTag(configTag: String): String? {
        val normalizedConfigTag = configTag.uppercase(Locale.US)
        terminalPrivateTags()[normalizedConfigTag]?.let { return it }
        return PinpadEmvDataObjects.findEncodedTlvValue(terminalConfigTlv(), normalizedConfigTag)
            ?.let { value -> with(PinpadEmvDataObjects) { value.toHex() } }
    }

    fun aidTlvs(): Map<String, String> = readMapFile(CONTACT_AIDS_FILE) ?: readMap(KEY_AID_TLVS)

    fun setAidTlv(id: String, tlv: String) {
        writeMap(KEY_AID_TLVS, aidTlvs() + (id.uppercase() to tlv.uppercase()))
        writeMapFile(CONTACT_AIDS_FILE, aidTlvs() + (id.uppercase() to tlv.uppercase()))
    }

    fun removeAidTlvs(ids: List<String>): List<Boolean> {
        val normalized = ids.map { it.uppercase() }
        val current = aidTlvs().toMutableMap()
        val result = normalized.map { current.remove(it) != null }
        writeMap(KEY_AID_TLVS, current)
        writeMapFile(CONTACT_AIDS_FILE, current)
        return result
    }

    fun pcdAidTlvs(): Map<String, String> = readMapFile(CONTACTLESS_AIDS_FILE).orEmpty()

    fun setPcdAidTlv(id: String, tlv: String) {
        writeMapFile(CONTACTLESS_AIDS_FILE, pcdAidTlvs() + (id.uppercase() to tlv.uppercase()))
    }

    fun removePcdAidTlvs(ids: List<String>): List<Boolean> {
        val normalized = ids.map { it.uppercase() }
        val current = pcdAidTlvs().toMutableMap()
        val result = normalized.map { current.remove(it) != null }
        writeMapFile(CONTACTLESS_AIDS_FILE, current)
        return result
    }

    fun capkTlvs(): Map<String, String> = readMapFile(CAPKS_FILE) ?: readMap(KEY_CAPK_TLVS)

    fun setCapkTlv(id: String, tlv: String) {
        writeMap(KEY_CAPK_TLVS, capkTlvs() + (id.uppercase() to tlv.uppercase()))
        writeMapFile(CAPKS_FILE, capkTlvs() + (id.uppercase() to tlv.uppercase()))
    }

    fun removeCapkTlvs(ids: List<String>): List<Boolean> {
        val normalized = ids.map { it.uppercase() }
        val current = capkTlvs().toMutableMap()
        val result = normalized.map { current.remove(it) != null }
        writeMap(KEY_CAPK_TLVS, current)
        writeMapFile(CAPKS_FILE, current)
        return result
    }

    fun pcdDrlTlvs(): Map<String, String> = readMapFile(CONTACTLESS_DRL_FILE).orEmpty()

    fun setPcdDrlTlv(id: String, tlv: String) {
        writeMapFile(CONTACTLESS_DRL_FILE, pcdDrlTlvs() + (id.uppercase() to tlv.uppercase()))
    }

    fun clearPcdDrlTlvs() {
        writeMapFile(CONTACTLESS_DRL_FILE, emptyMap())
    }

    fun allAidTlvs(): List<String> {
        return aidTlvs().values.toList() + pcdAidTlvs().values.toList()
    }

    private fun terminalPrivateTags(): Map<String, String> {
        return readMapFile(TERMINAL_PRIVATE_TAGS_FILE).orEmpty()
    }

    private fun kernelTerminalConfigTlv(tlvHex: String): String? {
        return PinpadEmvDataObjects.filterTlvRecords(tlvHex) { tag -> isTerminalPrivateTag(tag) }
    }

    private fun privateTerminalTags(tlvHex: String): Map<String, String> {
        return PinpadEmvDataObjects.tlvRecordValueMap(tlvHex) { tag -> isTerminalPrivateTag(tag) }.orEmpty()
    }

    private fun isTerminalPrivateTag(tag: String): Boolean {
        return TERMINAL_PRIVATE_TAG_PREFIXES.any { tag.startsWith(it) }
    }

    private fun readTextFile(fileName: String): String? {
        val file = File(configDir, fileName)
        return if (file.exists()) file.readText().trim() else null
    }

    private fun writeTextFile(fileName: String, value: String) {
        File(configDir, fileName).writeText(value)
    }

    private fun readMapFile(fileName: String): Map<String, String>? {
        val file = File(configDir, fileName)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        return json.keys().asSequence().associateWith { key -> json.optString(key) }
    }

    private fun writeMapFile(fileName: String, value: Map<String, String>) {
        val json = JSONObject()
        value.toSortedMap().forEach { (key, tlv) -> json.put(key, tlv) }
        File(configDir, fileName).writeText(json.toString(2))
    }

    private fun readDataFormatDefinitionsFile(
        fileName: String,
    ): Map<String, PinpadEmvDataObjects.EmvDataFormatDefinition>? {
        val file = File(configDir, fileName)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        return json.keys().asSequence().mapNotNull { key ->
            val item = json.opt(key)
            val definition = if (item is JSONObject) {
                PinpadEmvDataObjects.EmvDataFormatDefinition(
                    tag = item.optString("tag", key).uppercase(Locale.US),
                    formatCode = item.optString("formatCode"),
                    format = item.optString("format"),
                    minLength = item.optInt("minLength"),
                    maxLength = item.optInt("maxLength"),
                    lengthFlag = item.optString("lengthFlag"),
                    isVariableLength = item.optBoolean("isVariableLength"),
                    rule = item.optString("rule"),
                )
            } else {
                PinpadEmvDataObjects.parseDataFormatDefinition(key, json.optString(key))
            }
            definition?.tag?.let { it to definition }
        }.toMap()
    }

    private fun writeDataFormatDefinitionsFile(
        fileName: String,
        value: Map<String, PinpadEmvDataObjects.EmvDataFormatDefinition>,
    ) {
        val json = JSONObject()
        value.toSortedMap().forEach { (key, definition) ->
            json.put(
                key,
                JSONObject()
                    .put("tag", definition.tag)
                    .put("formatCode", definition.formatCode)
                    .put("format", definition.format)
                    .put("minLength", definition.minLength)
                    .put("maxLength", definition.maxLength)
                    .put("lengthFlag", definition.lengthFlag)
                    .put("isVariableLength", definition.isVariableLength)
                    .put("rule", definition.rule),
            )
        }
        File(configDir, fileName).writeText(json.toString(2))
    }

    private fun readMap(key: String): Map<String, String> {
        return prefs.getStringSet(key, emptySet()).orEmpty()
            .mapNotNull { row ->
                val separator = row.indexOf(MAP_SEPARATOR)
                if (separator <= 0) return@mapNotNull null
                row.substring(0, separator) to row.substring(separator + 1)
            }
            .toMap()
    }

    private fun writeMap(key: String, value: Map<String, String>) {
        val rows = value.map { (id, tlv) -> "$id$MAP_SEPARATOR$tlv" }.toSet()
        prefs.edit().putStringSet(key, rows).apply()
    }

    private companion object {
        private const val CONFIG_DIR_NAME = "pinpad_emv_config"
        private const val TERMINAL_CONFIG_FILE = "terminal_config.tlv"
        private const val TERMINAL_PRIVATE_TAGS_FILE = "terminal_private_tags.json"
        private const val DATA_FORMATS_FILE = "data_formats.json"
        private const val CONTACT_AIDS_FILE = "contact_aids.json"
        private const val CONTACTLESS_AIDS_FILE = "contactless_aids.json"
        private const val CAPKS_FILE = "capks.json"
        private const val CONTACTLESS_DRL_FILE = "contactless_drl.json"

        private const val PREFS_NAME = "pinpad_emv_config"
        private const val KEY_TERMINAL_CONFIG_TLV = "terminal_config_tlv"
        private const val KEY_DATA_FORMATS = "data_formats"
        private const val KEY_AID_TLVS = "aid_tlvs"
        private const val KEY_CAPK_TLVS = "capk_tlvs"
        private const val MAP_SEPARATOR = '|'
        private val TERMINAL_PRIVATE_TAG_PREFIXES = listOf("500000", "FFFF81")
    }
}
