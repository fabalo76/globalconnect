package com.uic.uicpaymentapp.transaction

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uic.tms.payment_app.TMS_Acquirer
import com.uic.tms.payment_app.TMS_Terminal
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.dao.TransactionRepository
import com.uic.uicpaymentapp.navigation.TRANSACTION_ID_KEY
import com.uic.uicpaymentapp.profile.Profile
import com.uic.uicpaymentapp.profile.profiledao.ProfileRepository
import com.uic.uicpaymentapp.printer.CUSTOMER
import com.uic.uicpaymentapp.printer.MERCHANT
import com.uic.uicpaymentapp.printer.NexGoPaymentPrinter
import com.uic.uicpaymentapp.printer.PaymentPrinter
import com.uic.uicpaymentapp.signature.SignatureRepository
import com.uic.uicpaymentapp.utils.FormatterUtils
import com.uic.uicpaymentapp.uicpos.pos.model.SignatureMode
import com.uic.uicpaymentapp.uicpos.pos.model.SysParam
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class FinishedPaymentViewModel(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val profileRepository: ProfileRepository,
    private val signatureRepository: SignatureRepository
) : ViewModel() {

    val transaction = androidx.lifecycle.MutableLiveData(Transaction())
    val subtotal = androidx.lifecycle.MutableLiveData("0.00")
    val tip = androidx.lifecycle.MutableLiveData("0.00")
    val subtotalPlusTip = androidx.lifecycle.MutableLiveData("0.00")

    private val transactionId: String = checkNotNull(savedStateHandle[TRANSACTION_ID_KEY])
    private val tmsDatabase = UICApplication.instance.container.tmsDatabase // ✅ Get TMS Database

    private val _receiptPreviewState = MutableStateFlow<ReceiptPreviewState?>(null)
    val receiptPreviewState: StateFlow<ReceiptPreviewState?> = _receiptPreviewState.asStateFlow()

    val tmsAcquirer: TMS_Acquirer? get() = tmsDatabase.Acquirer.firstOrNull()
    val tmsTerminal: TMS_Terminal? get() = tmsDatabase.Terminal.firstOrNull()

    companion object {
        private const val TAG = "FinishedPaymentViewModel"
    }

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Initializing")
                val retrievedTransaction = transactionRepository
                    .getTransactionFromId(transactionId.toIntOrNull() ?: return@withContext)
                    ?: return@withContext
                transaction.postValue(retrievedTransaction)
                subtotal.postValue(retrievedTransaction.subTotal)
                tip.postValue(retrievedTransaction.tipAmount)
                subtotalPlusTip.postValue(retrievedTransaction.totalAmount)
            }
        }
    }

    fun printCustomerReceipt() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                printReceipt(CUSTOMER)
            }
        }
    }

    fun printMerchantReceipt() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                printReceipt(MERCHANT)
            }
        }
    }

    private suspend fun printReceipt(recipient: String) {
        val id = transactionId.toIntOrNull()
        val retrievedTransaction = when {
            id != null -> transactionRepository.getTransactionFromId(id)
            else -> null
        } ?: transaction.value ?: return

        val profile = profileRepository.get() ?: Profile()
        val signature = signatureRepository.getSignatureFromTransactionId(transactionId)
        val paymentPrinter: PaymentPrinter = NexGoPaymentPrinter
        val context = UICApplication.instance.applicationContext

        val bitmap = signature?.let { loadSignatureBitmap(it.signatureUUID) }

        val previewState = buildReceiptPreviewState(
            transaction = retrievedTransaction,
            recipient = recipient,
            hasSignature = bitmap != null,
            signatureBitmap = bitmap,
        )
        previewState?.let { state ->
            _receiptPreviewState.emit(state)
        }

        runCatching {
            paymentPrinter.printReceipt(
                transaction = retrievedTransaction,
                profile = profile,
                tmsDatabase = tmsDatabase,
                bitmap = bitmap,
                recipient = recipient,
                context = context,
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to print $recipient receipt", error)
        }
    }

    fun dismissReceiptPreview() {
        _receiptPreviewState.value = null
    }

    private fun loadSignatureBitmap(signatureUuid: String?): androidx.compose.ui.graphics.ImageBitmap? {
        if (signatureUuid.isNullOrBlank()) {
            return null
        }
        val file = File(UICApplication.instance.filesDir, signatureUuid)
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }.onFailure { error ->
            Log.e(TAG, "Unable to decode signature bitmap", error)
        }.getOrNull()
    }

    private fun buildReceiptPreviewState(
        transaction: Transaction,
        recipient: String,
        hasSignature: Boolean,
        signatureBitmap: ImageBitmap?,
    ): ReceiptPreviewState? {
        val resources = UICApplication.instance.resources
        val terminal = tmsDatabase.Terminal.firstOrNull()
        val acquirer = tmsDatabase.Acquirer.firstOrNull()
        val currencySymbol = acquirer?.Currency?.takeIf { it.isNotBlank() } ?: "\$"

        val signatureRequired = transaction.type != TransactionType.REFUND &&
            transaction.returnStatus != ReturnStatus.Voided &&
            (SysParam.getInstance().signatureMode != SignatureMode.None || transaction.CVM == CVMType.Signature)

        val dateTime = runCatching {
            LocalDateTime.parse(transaction.localDateTime, com.uic.uicpaymentapp.records.dateTimeFormatter)
        }.getOrNull()

        val entryMode = resolveEntryModeLabel(transaction.cardEntryMethod)
        val batchValue = FormatterUtils.paddedInteger(1, 6)

        val tax1Amount = parseAmount(transaction.tax1Amount)
        val tax2Amount = parseAmount(transaction.tax2Amount)
        val tipAmount = parseAmount(transaction.tipAmount)
        val totalAmount = parseAmount(transaction.totalAmount)
        val tax1Discount = parseAmount(transaction.tax1DiscountAmount)?.negate()

        val emvEntry = transaction.cardEntryMethod.equals("EMV", true) ||
            transaction.cardEntryMethod.equals("EMV_CONTACTLESS", true)

        val lines = buildList {
            fun addLine(text: String?, alignment: TextAlign = TextAlign.Center) {
                if (!text.isNullOrBlank()) {
                    add(ReceiptPreviewLine(primary = text.trim(), alignment = alignment))
                }
            }

            addLine(terminal?.MerchantTitle1)
            addLine(terminal?.MerchantTitle2)
            addLine(terminal?.MerchantTitle3)
            addLine(acquirer?.AcqLine1)

            val merchantId = acquirer?.MerchID?.takeIf { it.isNotBlank() }
            val terminalId = acquirer?.AcqTermID?.takeIf { it.isNotBlank() }
            if (merchantId != null || terminalId != null) {
                add(ReceiptPreviewLine(primary = merchantId.orEmpty(), secondary = terminalId))
            }

            dateTime?.let {
                add(
                    ReceiptPreviewLine(
                        primary = it.format(com.uic.uicpaymentapp.records.dateFormatter),
                        secondary = it.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                    ),
                )
            }

            if (entryMode.isNotBlank()) {
                val batchLabel = resources.getString(R.string.batch)
                add(
                    ReceiptPreviewLine(
                        primary = entryMode,
                        secondary = "$batchLabel#: $batchValue",
                    ),
                )
            }

            if (transaction.masked_cardNumber.isNotBlank() || transaction.cardType.isNotBlank()) {
                add(
                    ReceiptPreviewLine(
                        primary = transaction.masked_cardNumber.ifBlank { "" },
                        secondary = transaction.cardType.ifBlank { null },
                    ),
                )
            }

            val invoiceLabel = resources.getString(R.string.invoice_short)
            val rrnValue = transaction.transactionId.ifBlank { transaction.retrievalReferenceNumber }
            if (rrnValue.isNotBlank() || transaction.invoiceId.isNotBlank()) {
                add(
                    ReceiptPreviewLine(
                        primary = "RRN: ${rrnValue.ifBlank { "----" }}",
                        secondary = "$invoiceLabel: ${transaction.invoiceId.ifBlank { "----" }}",
                    ),
                )
            }

            val extRefLabel = resources.getString(R.string.ext_ref)
            val authLabel = resources.getString(R.string.auth_code_short)
            if (transaction.externalReferenceNumber.isNotBlank() || transaction.authCode.isNotBlank()) {
                add(
                    ReceiptPreviewLine(
                        primary = "$extRefLabel: ${transaction.externalReferenceNumber.ifBlank { "----" }}",
                        secondary = "$authLabel: ${transaction.authCode.ifBlank { "----" }}",
                    ),
                )
            }

            add(ReceiptPreviewLine(primary = ""))

            if (transaction.returnStatus == ReturnStatus.Voided) {
                addLine(resources.getString(R.string.receipt_void_prefix), TextAlign.Start)
            }

            if (transaction.type != TransactionType.ERROR) {
                add(
                    ReceiptPreviewLine(
                        primary = transaction.type.toStringForUsers(),
                        secondary = FormatterUtils.formatAmount(currencySymbol, transaction.subTotal.ifBlank { transaction.totalAmount }),
                        emphasis = true,
                    ),
                )
            }

            if (tax1Amount != null && tax1Amount > BigDecimal.ZERO) {
                add(
                    ReceiptPreviewLine(
                        primary = " ${resources.getString(R.string.Tax).uppercase(Locale.getDefault())}",
                        secondary = FormatterUtils.formatAmount(currencySymbol, tax1Amount),
                    ),
                )
            }

            if (tax1Discount != null && tax1Discount < BigDecimal.ZERO) {
                add(
                    ReceiptPreviewLine(
                        primary = "  ${resources.getString(R.string.Tax_Discount).uppercase(Locale.getDefault())}",
                        secondary = FormatterUtils.formatAmount(currencySymbol, tax1Discount),
                    ),
                )
            }

            if (tax2Amount != null && tax2Amount > BigDecimal.ZERO) {
                add(
                    ReceiptPreviewLine(
                        primary = " ${resources.getString(R.string.Tax2).uppercase(Locale.getDefault())}",
                        secondary = FormatterUtils.formatAmount(currencySymbol, tax2Amount),
                    ),
                )
            }

            if (tipAmount != null && tipAmount > BigDecimal.ZERO) {
                add(
                    ReceiptPreviewLine(
                        primary = " ${resources.getString(R.string.Tip).uppercase(Locale.getDefault())}",
                        secondary = FormatterUtils.formatAmount(currencySymbol, tipAmount),
                    ),
                )
            }

            add(ReceiptPreviewLine(primary = "-".repeat(24), alignment = TextAlign.End))

            totalAmount?.let {
                add(
                    ReceiptPreviewLine(
                        primary = resources.getString(R.string.Total).uppercase(Locale.getDefault()),
                        secondary = FormatterUtils.formatAmount(currencySymbol, it),
                        emphasis = true,
                    ),
                )
            }

            if (transaction.type != TransactionType.REFUND && transaction.returnStatus != ReturnStatus.Voided) {
                if (transaction.CVM == CVMType.PinVerified) {
                    add(
                        ReceiptPreviewLine(
                            primary = resources.getString(R.string.PinVerified),
                            emphasis = true,
                        ),
                    )
                }

                if (signatureRequired && !hasSignature) {
                    add(ReceiptPreviewLine(primary = ""))
                    add(ReceiptPreviewLine(primary = ""))
                    val signatureLabel = resources.getString(R.string.receipt_signature)
                    val underline = "_".repeat((32 - signatureLabel.length).coerceAtLeast(4))
                    add(ReceiptPreviewLine(primary = signatureLabel + underline, alignment = TextAlign.Start))

                    listOf(
                        resources.getString(R.string.receipt_agreement_1),
                        resources.getString(R.string.receipt_agreement_2),
                        resources.getString(R.string.receipt_agreement_3),
                    ).forEach { line ->
                        if (line.isNotBlank()) {
                            add(
                                ReceiptPreviewLine(
                                    primary = line.uppercase(Locale.getDefault()),
                                ),
                            )
                        }
                    }
                }
            }

            if (emvEntry) {
                val emvLabel = resources.getString(R.string.emv_information)
                val filler = "=".repeat(((32 - emvLabel.length).coerceAtLeast(0)) / 2)
                add(ReceiptPreviewLine(primary = "$filler$emvLabel$filler"))
                add(
                    ReceiptPreviewLine(
                        primary = transaction.applicationName,
                        secondary = transaction.AID,
                    ),
                )
                add(
                    ReceiptPreviewLine(
                        primary = "${resources.getString(R.string.TVR)} ${transaction.TVR}",
                        secondary = "${resources.getString(R.string.TSI)} ${transaction.TSI}",
                    ),
                )
                add(
                    ReceiptPreviewLine(
                        primary = "${resources.getString(R.string.ARC)} ${transaction.ARC}",
                        secondary = "${resources.getString(R.string.IAD)} ${transaction.IAD}",
                    ),
                )
                add(ReceiptPreviewLine(primary = "=".repeat(32)))
            }

            val copyLabel = if (recipient == MERCHANT) {
                resources.getString(R.string.merchant_copy)
            } else {
                resources.getString(R.string.customer_copy)
            }
            add(ReceiptPreviewLine(primary = copyLabel))

            addLine(resources.getString(R.string.poweredbyuic))

            val version = resources.getString(R.string.version)
            val appVersion = UICApplication.instance.appVersion
            add(ReceiptPreviewLine(primary = "$version: $appVersion"))
        }

        if (lines.isEmpty()) {
            return null
        }

        return ReceiptPreviewState(
            lines = lines,
            recipientLabel = "",
            transactionLabel = "",
            signature = signatureBitmap,
            signatureRequired = signatureRequired,
        )
    }

    private fun resolveEntryModeLabel(method: String): String {
        if (method.isBlank()) {
            return ""
        }
        val resources = UICApplication.instance.resources
        val modeLabel = when (method.uppercase(Locale.getDefault())) {
            "SWIPE" -> resources.getString(R.string.short_entrymode_MSR)
            "NFC" -> resources.getString(R.string.short_entrymode_NFC)
            "EMV" -> resources.getString(R.string.short_entrymode_EMV)
            "EMV_CONTACTLESS" -> resources.getString(R.string.short_entrymode_EMVCTLS)
            "MANUAL" -> resources.getString(R.string.short_entrymode_MANUAL)
            "FALLBACK_SWIPE" -> resources.getString(R.string.short_entrymode_FALLBACK)
            else -> resources.getString(R.string.short_entrymode_UNKNOWN)
        }
        return resources.getString(R.string.entrymode_short, modeLabel)
    }

    private fun parseAmount(raw: String?): BigDecimal? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching {
            raw.replace(",", "").toBigDecimal()
        }.getOrNull()
    }

}

