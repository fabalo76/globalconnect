package one.globalconnect.paymentapp.printer

import one.globalconnect.tms.paymentapp.TMSDATA

internal data class ConfigurationReportField(
    val label: String,
    val value: String,
)

internal data class ConfigurationReportSection(
    val title: String,
    val fields: List<ConfigurationReportField>,
)

internal fun buildConfigurationReportSections(
    database: TMSDATA,
    invoiceNumber: String,
    stan: String,
): List<ConfigurationReportSection> = buildList {
    addSection("APPLICATION") {
        field("Application ID", database.applicationId)
        field("Schema version", database.schemaVersion)
        field("Terminals", database.terminal.size)
        field("Acquirers", database.Acquirer.size)
        field("Invoice", invoiceNumber)
        field("STAN", stan)
    }

    database.terminal.forEachIndexed { terminalIndex, terminal ->
        addSection("TERMINAL ${terminalIndex + 1}") {
            field("Terminal ID", terminal.TermID)
            field("Header L1", terminal.headerLine1)
            field("Header L2", terminal.headerLine2)
            field("Header L3", terminal.headerLine3)
            field("Header L4", terminal.headerLine4)
            field("Sale enabled", terminal.enableSale)
            field("Cash enabled", terminal.enableCash)
            field("Payment enabled", terminal.enablePayment)
            field("Refund enabled", terminal.enableRefund)
            field("Loyalty enabled", terminal.enableLoyalty)
            field("Installments enabled", terminal.enableInstallments)
            field("Check-in/out enabled", terminal.enableCheckInOut)
            field("Tip processing mode", terminal.tipProcessingMode)
            field("Print receipt", terminal.printReceipt)
            field("Print customer copy", terminal.printCustomerCopy)
            field("Print reports", terminal.printReports)
            field("Print reversal", terminal.printReversal)
            field("CAPK mode", terminal.capkMode)
        }

        addSection("TERMINAL ${terminalIndex + 1} TAX") {
            field("Tax 1 enabled", terminal.tax1Enabled)
            field("Tax 1 mandatory", terminal.tax1Mandatory)
            field("Tax 1 max %", terminal.tax1MaxPercentage)
            field("Tax 1 discount %", terminal.tax1DiscountPercentage)
            field("Tax 2 enabled", terminal.tax2Enabled)
            field("Tax 2 mandatory", terminal.tax2Mandatory)
            field("Tax 2 max %", terminal.tax2MaxPercentage)
        }

        addSection("TERMINAL ${terminalIndex + 1} CAPABILITIES") {
            field("Online PIN", terminal.onlinePinCap)
            field("Signature", terminal.signatureCap)
            field("No CVM", terminal.noCVMCap)
            field("Offline enciphered PIN", terminal.offlineEncrPinCap)
            field("Offline plaintext PIN", terminal.offlineClearPinCap)
            field("Offline PIN change", terminal.enableOfflinePinChange)
            field("Offline PIN unblock", terminal.enableOfflinePinUnblock)
        }

        addSection("TERMINAL ${terminalIndex + 1} REPORTING") {
            field("Method", terminal.tranReportingMethod)
            field("Batch size", terminal.tranReportingBatchSize)
            field("Interval seconds", terminal.tranReportingIntervalSeconds)
        }

        terminal.acquirer.forEachIndexed { acquirerIndex, acquirer ->
            addSection("ACQUIRER ${acquirerIndex + 1} [${acquirer.AcqID}]") {
                field("Name", acquirer.acquirerName)
                field("Header line 1", acquirer.headerLine1)
                field("Header line 2", acquirer.headerLine2)
                field("Merchant ID", acquirer.merchantId)
                field("Terminal ID", acquirer.terminalId)
                field("Host protocol", acquirer.hostProtocol)
                field("Transaction server", serverName(database, acquirer.hostConnectionInfoRef))
                field(
                    "Settlement server",
                    serverName(
                        database,
                        acquirer.settlementHostConnectionInfoRef.ifBlank { acquirer.hostConnectionInfoRef },
                    ),
                )
                field("NII", acquirer.nii)
                field("Country code", acquirer.countryCode.toString().padStart(4, '0'))
                field("Currency code", acquirer.currencyCode.toString().padStart(4, '0'))
                field("Currency symbol", acquirer.currencySymbol)
                field("Initial batch", acquirer.initialBatchNumber.toString().padStart(6, '0'))
                field("Tip processing mode", acquirer.tipProcessingMode)
                field("Send entry capability", acquirer.sendAcquirerEntryCapability)
                field("Entry capability", acquirer.acquirerEntryCapability)
                field("Send legacy entry cap", acquirer.sendAqEntryCap)
                field("Legacy entry cap", acquirer.acqEntryCap)
                field("Restricted BINs", acquirer.restrictedBins)
                field("Fallback blocked BINs", acquirer.blockFallbackToBins)
                field("EMV enabled", acquirer.emvFeature)
                field("Fallback allowed", acquirer.allowFallback)
                field("PIN type", acquirer.pinType)
                field("Key index", acquirer.nexgoPinKeyIndex?.toString())
            }

            addSection("ACQUIRER ${acquirer.AcqID} TRANSACTIONS") {
                field("Sale", acquirer.enableSale)
                field("Cash", acquirer.enableCash)
                field("Payment", acquirer.enablePayment)
                field("Refund", acquirer.enableRefund)
                field("Loyalty", acquirer.enableLoyalty)
                field("Installments", acquirer.enableInstallments)
                field("Check-in/out", acquirer.enableCheckInOut)
                field("Balance", acquirer.enableBalance)
            }

        }
    }
}

private class SectionBuilder {
    val fields = mutableListOf<ConfigurationReportField>()

    fun field(label: String, value: Any?) {
        fields += ConfigurationReportField(label, printableValue(value))
    }
}

private fun MutableList<ConfigurationReportSection>.addSection(
    title: String,
    block: SectionBuilder.() -> Unit,
) {
    val builder = SectionBuilder().apply(block)
    add(ConfigurationReportSection(title, builder.fields))
}

private fun printableValue(value: Any?): String = when (value) {
    null -> "-"
    is Boolean -> if (value) "YES" else "NO"
    is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    else -> value.toString().takeIf { it.isNotBlank() } ?: "-"
}

private fun serverName(database: TMSDATA, serverReference: String): String {
    if (serverReference.isBlank()) return "-"
    val server = database.host_connection_info.firstOrNull { it.config_id == serverReference }
    return server?.description?.takeIf { it.isNotBlank() }
        ?: server?.config_id
        ?: serverReference
}
