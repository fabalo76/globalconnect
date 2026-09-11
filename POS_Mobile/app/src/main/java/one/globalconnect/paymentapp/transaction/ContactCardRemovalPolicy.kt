package one.globalconnect.paymentapp.transaction

/** Contact-chip transactions must release the ICC card before another card flow can start. */
internal fun requiresContactCardRemoval(cardEntryMethod: String): Boolean =
    cardEntryMethod.equals("EMV", ignoreCase = true)
