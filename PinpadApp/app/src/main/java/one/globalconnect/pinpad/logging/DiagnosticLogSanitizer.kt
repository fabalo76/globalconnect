package one.globalconnect.pinpad.logging

/** File diagnostics must never reuse the raw serial/logcat trace. */
internal object DiagnosticLogSanitizer {
    private val pinKeyEvent = Regex("(?i)(PIN.*key event|keyCode\\s*=|keypad key\\s*=)")
    private val assignment = Regex("([A-Za-z0-9_]+)=(\\\"[^\\\"]*\\\"|\\S*)")
    private val pan = Regex("(?<![0-9])[0-9]{12,19}(?![0-9])")
    private val longHex = Regex("(?i)(?<![0-9a-f])[0-9a-f]{16,}(?![0-9a-f])")
    private val tagAssignment = Regex("\\btag=([0-9A-Fa-f]+)\\b")
    private val sensitiveTags = setOf("5A", "57", "56", "9F6B", "9F26", "9F10")
    private val sensitiveNames = setOf(
        "pan", "account", "accountnumber", "cardno", "track", "track1", "track2",
        "pin", "pinblock", "encryptedpinblock", "password", "secret", "token",
        "key", "sessionkey", "encryptedsessionkey", "keydata", "payload", "hex",
        "ascii", "data", "tlv", "tlvhex", "value", "message", "label", "labels",
    )
    private val safeTags = setOf(
        "4F", "84", "9F06", "95", "9B", "9F34", "9F27", "9F33", "9F35",
        "9F66", "9F1D", "9F09", "9F1A", "5F2A", "5F36", "DF8118", "DF8119",
        "DF811B", "DF8120", "DF8121", "DF8122", "DF8115", "DF8116", "DF8129",
    )

    fun sanitize(message: String): String {
        if (pinKeyEvent.containsMatchIn(message)) return "PIN/keypad input event [redacted]"
        val tag = tagAssignment.find(message)?.groupValues?.get(1)?.uppercase()
        val flattened = message.replace('\n', ' ').replace('\r', ' ')
        val fields = assignment.replace(flattened) { match ->
            val name = match.groupValues[1]
            val raw = match.groupValues[2]
            // Group public application IDs so PAN/secret detection does not eat their digits.
            if ((name.lowercase() in setOf("aid", "rid", "4f", "84", "9f06") || (name == "value" && tag in setOf("4F", "84", "9F06"))) &&
                raw.startsWith("A000", ignoreCase = true) && raw.length in 10..32 && raw.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) {
                return@replace "$name=${raw.chunked(2).joinToString(":")}"
            }
            val sensitiveTag = name.uppercase() in sensitiveTags
            val sensitiveField = name.lowercase() in sensitiveNames && !(name == "value" && tag in safeTags)
            if (sensitiveField || sensitiveTag) "$name=[redacted]" else match.value
        }
        return longHex.replace(pan.replace(fields, "[redacted]"), "[redacted]").take(1800)
    }
}

internal class DetailedLogRecorder(
    private val enabled: () -> Boolean,
    private val verbose: () -> Boolean,
    private val write: (String) -> Unit,
    private val onFailure: (Exception) -> Unit = {},
) {
    fun record(message: String) {
        try {
            if (enabled() && verbose()) write("DETAIL ${DiagnosticLogSanitizer.sanitize(message)}")
        } catch (error: Exception) {
            // Diagnostics must not interrupt a payment or serial recovery.
            onFailure(error)
        }
    }

    fun recordNormal(message: String) {
        try {
            if (enabled()) write(DiagnosticLogSanitizer.sanitize(message))
        } catch (error: Exception) {
            onFailure(error)
        }
    }
}
