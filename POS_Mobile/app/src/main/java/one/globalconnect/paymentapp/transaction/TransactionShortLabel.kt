package one.globalconnect.paymentapp.transaction

import one.globalconnect.paymentapp.uicpos.pos.host.TransactionConfigRegistry
import java.util.Locale

fun Transaction.shortReportLabel(): String =
    TransactionConfigRegistry.configFor(type.toTransactionString())?.shortLabel
        ?: type.name.take(3).uppercase(Locale.US)
