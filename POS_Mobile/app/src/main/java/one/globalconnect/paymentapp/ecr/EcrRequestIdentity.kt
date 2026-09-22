package one.globalconnect.paymentapp.ecr

internal fun EcrMessage.validateRequestId() {
    fields["RQ"]?.let { id ->
        require(id.isNotBlank() && id.length <= 64 && id.all { it.code in 32..126 })
    }
}

/** RQ is the API request identity; 80 remains the POS transaction reference. */
internal fun EcrMessage.journalIdentity(): String = fields["RQ"]?.let { "RQ:$it" }
    ?: if (command == "20") fields["80"].orEmpty() else "$command:${fields["80"].orEmpty()}"

internal fun EcrMessage.withRequestIdFrom(request: EcrMessage): EcrMessage =
    request.fields["RQ"]?.let { copy(fields = fields + ("RQ" to it)) } ?: this
