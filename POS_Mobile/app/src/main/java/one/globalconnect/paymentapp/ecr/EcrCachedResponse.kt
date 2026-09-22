package one.globalconnect.paymentapp.ecr

/** Correct only the old confirmation expiry that explicitly guarantees no host submission. */
internal fun EcrMessage.normalizeLegacyVoidCancellation(): EcrMessage {
    if (command != "42" || indicator != 1 || response != "TO" || more ||
        fields["00"] != "TO" ||
        fields["02"] != "Void confirmation timed out; nothing sent to host"
    ) return this

    return copy(response = "UC", fields = fields + mapOf("00" to "UC", "02" to "Void cancelled"))
}
