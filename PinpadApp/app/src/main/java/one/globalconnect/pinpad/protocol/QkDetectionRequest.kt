package one.globalconnect.pinpad.protocol

/** Optional FS-separated QK fields. Display text never changes transaction processing data. */
data class QkDetectionRequest(
    val interfaces: String = "111",
    val transactionName: String? = null,
    val formattedAmount: String? = null,
) {
    val swipe get() = interfaces[0] == '1'
    val chip get() = interfaces[1] == '1'
    val contactless get() = interfaces[2] == '1'

    companion object {
        fun parse(payload: String): QkDetectionRequest? {
            val fields = payload.split('\u001C')
            if (fields.size > 3) return null
            val mask = fields[0].ifEmpty { "111" }
            if (!mask.matches(Regex("[01]{3}")) || mask == "000") return null
            val name = fields.getOrNull(1)?.takeIf { it.isNotBlank() }
            val amount = fields.getOrNull(2)?.takeIf { it.isNotBlank() }
            if ((name?.length ?: 0) > 64 || (amount?.length ?: 0) > 48) return null
            if (listOfNotNull(name, amount).any { value -> value.any { it.isISOControl() } }) return null
            return QkDetectionRequest(mask, name, amount)
        }
    }
}
