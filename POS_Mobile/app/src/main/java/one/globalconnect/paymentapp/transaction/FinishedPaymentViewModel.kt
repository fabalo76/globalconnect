package one.globalconnect.paymentapp.transaction

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_Terminal
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.dao.TransactionRepository
import one.globalconnect.paymentapp.navigation.TRANSACTION_ID_KEY
import one.globalconnect.paymentapp.profile.Profile
import one.globalconnect.paymentapp.profile.profiledao.ProfileRepository
import one.globalconnect.paymentapp.printer.CUSTOMER
import one.globalconnect.paymentapp.printer.MERCHANT
import one.globalconnect.paymentapp.printer.NexGoPaymentPrinter
import one.globalconnect.paymentapp.printer.PaymentPrinter
import one.globalconnect.paymentapp.signature.SignatureRepository
import one.globalconnect.paymentapp.utils.FormatterUtils
import one.globalconnect.paymentapp.uicpos.pos.model.SignatureMode
import one.globalconnect.paymentapp.uicpos.pos.model.SysParam
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
    private val savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val profileRepository: ProfileRepository,
    private val signatureRepository: SignatureRepository
) : ViewModel() {

    val transaction = androidx.lifecycle.MutableLiveData(Transaction())
    val subtotal = androidx.lifecycle.MutableLiveData("0.00")
    val tip = androidx.lifecycle.MutableLiveData("0.00")
    val subtotalPlusTip = androidx.lifecycle.MutableLiveData("0.00")

    private val transactionId: String = checkNotNull(savedStateHandle[TRANSACTION_ID_KEY])
    private val tmsDatabase = GlobalConnectPaymentApplication.instance.container.tmsDatabase // ✅ Get TMS Database

    private val _receiptPreviewState = MutableStateFlow<ReceiptPreviewState?>(null)
    val receiptPreviewState: StateFlow<ReceiptPreviewState?> = _receiptPreviewState.asStateFlow()
    private val automaticMerchantReceipt = AutomaticMerchantReceipt(viewModelScope)
    private val merchantPrintState = MerchantReceiptPrintState(
        savedStateHandle.get<Boolean>("merchantReceiptPrinted") == true,
    )
    val merchantReceiptPrintStatus = merchantPrintState.status

    val tmsAcquirer: TMS_Acquirer? get() = tmsDatabase.Acquirer.firstOrNull()
    val tmsTerminal: TMS_Terminal? get() = tmsDatabase.Terminal.firstOrNull()

    companion object {
        private const val TAG = "FinishedPaymentViewModel"
        private const val PREVIEW_SMALL_FONT_LINE_WIDTH = 32
        private const val PREVIEW_TINY_FONT_LINE_WIDTH = 38
    }

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Initializing")
                val retrievedTransaction = TransientTransactionResultStore.consume(transactionId)
                    ?: transactionId.toIntOrNull()
                        ?.let { id -> transactionRepository.getTransactionFromId(id) }
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
            submitMerchantReceipt(showPreview = true)
        }
    }

    private suspend fun submitMerchantReceipt(showPreview: Boolean) {
        if (!merchantPrintState.tryStart()) return
        try {
            withContext(Dispatchers.IO) {
                printReceipt(MERCHANT, showPreview) { success ->
                    viewModelScope.launch {
                        merchantPrintState.complete(success)
                        if (success) savedStateHandle["merchantReceiptPrinted"] = true
                    }
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            merchantPrintState.complete(false)
            Log.e(TAG, "Unable to submit merchant receipt", error)
        }
    }

    suspend fun submitAutomaticMerchantReceipt() {
        if (transaction.value?.type == TransactionType.ERROR || transaction.value == null) return
        automaticMerchantReceipt.submit(tmsTerminal?.printReceipt == true) {
            if (savedStateHandle.get<Boolean>("automaticMerchantReceiptSubmitted") == true) return@submit
            // Claim before submission: printer failures remain retryable through the manual button.
            savedStateHandle["automaticMerchantReceiptSubmitted"] = true
            try {
                submitMerchantReceipt(showPreview = false)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Unable to submit automatic merchant receipt", error)
            }
        }
    }

    private suspend fun printReceipt(
        recipient: String,
        showPreview: Boolean = true,
        onPrintResult: ((Boolean) -> Unit)? = null,
    ) {
        val id = transactionId.toIntOrNull()
        val retrievedTransaction = when {
            id != null -> transactionRepository.getTransactionFromId(id)
            else -> null
        } ?: transaction.value ?: run {
            onPrintResult?.invoke(false)
            return
        }

        val profile = profileRepository.get() ?: Profile()
        val signature = signatureRepository.getSignatureFromTransactionId(transactionId)
        val paymentPrinter: PaymentPrinter = NexGoPaymentPrinter
        val context = GlobalConnectPaymentApplication.instance.applicationContext

        val bitmap = signature?.let { loadSignatureBitmap(it.signatureUUID) }

        val previewState = if (showPreview) buildReceiptPreviewState(
            transaction = retrievedTransaction,
            recipient = recipient,
            hasSignature = bitmap != null,
            signatureBitmap = bitmap,
        ) else null
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
                onPrintResult = onPrintResult,
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to print $recipient receipt", error)
            onPrintResult?.invoke(false)
        }
    }

    fun dismissReceiptPreview() {
        _receiptPreviewState.value = null
    }

    private fun loadSignatureBitmap(signatureUuid: String?): androidx.compose.ui.graphics.ImageBitmap? {
        if (signatureUuid.isNullOrBlank()) {
            return null
        }
        val file = File(GlobalConnectPaymentApplication.instance.filesDir, signatureUuid)
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
        val resources = GlobalConnectPaymentApplication.instance.resources
        val terminal = tmsDatabase.Terminal.firstOrNull()
        val acquirer = tmsDatabase.Acquirer.firstOrNull()
        val currencySymbol = acquirer?.Currency?.takeIf { it.isNotBlank() } ?: "\$"

        val cvmPresentation = resolveReceiptCvmPresentation(
            transaction = transaction,
            configuredSignatureRequired = SysParam.getInstance().signatureMode != SignatureMode.None,
        )
        val signatureRequired = transaction.type != TransactionType.REFUND &&
            transaction.returnStatus != ReturnStatus.Voided &&
            cvmPresentation.signatureRequired

        val dateTime = runCatching {
            LocalDateTime.parse(transaction.localDateTime, one.globalconnect.paymentapp.records.dateTimeFormatter)
        }.getOrNull()

        val entryMode = resolveEntryModeLabel(transaction.cardEntryMethod)
        val batchValue = FormatterUtils.paddedInteger(1, 6)

        val tax1Amount = parseAmount(
            Tax1DiscountCalculator.originalTaxAmountFromDiscounted(
                discountedTaxAmount = transaction.tax1Amount,
                discountAmount = transaction.tax1DiscountAmount,
            ),
        )
        val tax2Amount = parseAmount(transaction.tax2Amount)
        val tipAmount = parseAmount(transaction.tipAmount)
        val totalAmount = parseAmount(transaction.totalAmount)
        val baseAmount = parseAmount(transaction.baseAmount)
            ?: parseAmount(transaction.subTotal)
            ?: totalAmount
        val tax1Discount = parseAmount(transaction.tax1DiscountAmount)?.negate()

        val emvEntry = transaction.cardEntryMethod.equals("EMV", true) ||
            transaction.cardEntryMethod.equals("EMV_CONTACTLESS", true)

        val lines = buildList {
            fun addLine(
                text: String?,
                alignment: TextAlign = TextAlign.Center,
                fontSize: ReceiptPreviewFontSize = ReceiptPreviewFontSize.SMALL,
            ) {
                if (!text.isNullOrBlank()) {
                    add(
                        ReceiptPreviewLine(
                            primary = text.trim(),
                            alignment = alignment,
                            fontSize = fontSize,
                        ),
                    )
                }
            }

            addLine(terminal?.MerchantTitle1, fontSize = ReceiptPreviewFontSize.MEDIUM)
            addLine(terminal?.MerchantTitle2, fontSize = ReceiptPreviewFontSize.MEDIUM)
            addLine(terminal?.MerchantTitle3, fontSize = ReceiptPreviewFontSize.MEDIUM)
            addLine(acquirer?.AcqLine1, fontSize = ReceiptPreviewFontSize.MEDIUM)

            val merchantId = acquirer?.MerchID?.takeIf { it.isNotBlank() }
            val terminalId = acquirer?.AcqTermID?.takeIf { it.isNotBlank() }
            if (merchantId != null || terminalId != null) {
                add(
                    ReceiptPreviewLine(
                        primary = merchantId.orEmpty(),
                        secondary = terminalId,
                        fontSize = ReceiptPreviewFontSize.SMALL,
                    ),
                )
            }

            dateTime?.let {
                add(
                    ReceiptPreviewLine(
                        primary = it.format(DateTimeFormatter.ofPattern(resources.getString(R.string.receipt_date_pattern))),
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
                        fontSize = ReceiptPreviewFontSize.TINY,
                    ),
                )
            }

            if (transaction.masked_cardNumber.isNotBlank() || transaction.cardType.isNotBlank()) {
                add(
                    ReceiptPreviewLine(
                        primary = transaction.masked_cardNumber.ifBlank { "" },
                        secondary = transaction.cardType.ifBlank { null },
                        fontSize = ReceiptPreviewFontSize.MEDIUM,
                    ),
                )
            }

            val invoiceLabel = resources.getString(R.string.invoice_short)
            val rrnValue = transaction.retrievalReferenceNumber.trim()
            if (rrnValue.isNotBlank() || transaction.invoiceId.isNotBlank()) {
                add(
                    ReceiptPreviewLine(
                        primary = "RRN: ${rrnValue.ifBlank { "----" }}",
                        secondary = "$invoiceLabel: ${transaction.invoiceId.ifBlank { "----" }}",
                        fontSize = ReceiptPreviewFontSize.TINY,
                    ),
                )
            }

            val authLabel = resources.getString(R.string.auth_code_short)
            if (transaction.authCode.isNotBlank()) {
                add(
                    ReceiptPreviewLine(
                        primary = "",
                        secondary = "$authLabel: ${transaction.authCode.trim()}",
                        fontSize = ReceiptPreviewFontSize.TINY,
                    ),
                )
            }

            val receiptReferences = resolveReceiptReferenceValues(
                folioNumber = transaction.folioNumber,
                externalReferenceNumber = transaction.externalReferenceNumber,
            )
            receiptReferences.folioNumber?.let { folioNumber ->
                add(
                    ReceiptPreviewLine(
                        primary = "${resources.getString(R.string.hotel_check_in_report_detail_folio_label)}: $folioNumber",
                        emphasis = true,
                        alignment = TextAlign.Start,
                        fontSize = ReceiptPreviewFontSize.SMALL,
                    ),
                )
            }
            receiptReferences.externalReferenceNumber?.let { externalReferenceNumber ->
                add(
                    ReceiptPreviewLine(
                        primary = "${resources.getString(R.string.ext_ref)}: $externalReferenceNumber",
                        emphasis = true,
                        alignment = TextAlign.Start,
                        fontSize = ReceiptPreviewFontSize.SMALL,
                    ),
                )
            }

            add(ReceiptPreviewLine(primary = ""))

            if (transaction.returnStatus == ReturnStatus.Voided) {
                addLine(resources.getString(R.string.receipt_void_prefix), TextAlign.Start)
            }

            val isLoyaltyBalance = transaction.type == TransactionType.LOYALTY_BALANCE
            val partialApproval = transaction.partialApprovalReceipt()
            if (partialApproval != null) {
                add(ReceiptPreviewLine(primary = resources.getString(R.string.receipt_partial_approved), emphasis = true))
                add(ReceiptPreviewLine(primary = resources.getString(R.string.receipt_verify_amount), emphasis = true))
                add(ReceiptPreviewLine(primary = ""))
            }
            if (transaction.type != TransactionType.ERROR) {
                add(
                    ReceiptPreviewLine(
                        primary = transaction.type.toStringForUsers(),
                        secondary = if (isLoyaltyBalance) null else {
                            FormatterUtils.formatAmount(currencySymbol, baseAmount ?: BigDecimal.ZERO)
                        },
                        emphasis = true,
                        fontSize = ReceiptPreviewFontSize.LARGE,
                    ),
                )
            }

            if (isLoyaltyBalance && transaction.loyaltyBalancePoints.isNotBlank()) {
                add(
                    ReceiptPreviewLine(
                        primary = resources.getString(
                            R.string.loyalty_points_available,
                            LoyaltyContract.formatPoints(transaction.loyaltyBalancePoints),
                        ),
                        emphasis = true,
                        alignment = TextAlign.Center,
                        fontSize = ReceiptPreviewFontSize.LARGE,
                    ),
                )
            }

            if (!isLoyaltyBalance && tax1Amount != null && tax1Amount > BigDecimal.ZERO) {
                add(
                    ReceiptPreviewLine(
                        primary = " ${resources.getString(R.string.Tax).uppercase(Locale.getDefault())}",
                        secondary = FormatterUtils.formatAmount(currencySymbol, tax1Amount),
                    ),
                )
            }

            if (!isLoyaltyBalance && tax1Discount != null && tax1Discount < BigDecimal.ZERO) {
                add(
                    ReceiptPreviewLine(
                        primary = "  ${resources.getString(R.string.Tax_Discount).uppercase(Locale.getDefault())}",
                        secondary = FormatterUtils.formatAmount(currencySymbol, tax1Discount),
                    ),
                )
            }

            if (!isLoyaltyBalance && tax2Amount != null && tax2Amount > BigDecimal.ZERO) {
                add(
                    ReceiptPreviewLine(
                        primary = " ${resources.getString(R.string.Tax2).uppercase(Locale.getDefault())}",
                        secondary = FormatterUtils.formatAmount(currencySymbol, tax2Amount),
                    ),
                )
            }

            if (!isLoyaltyBalance && tipAmount != null && tipAmount > BigDecimal.ZERO) {
                add(
                    ReceiptPreviewLine(
                        primary = " ${resources.getString(R.string.Tip).uppercase(Locale.getDefault())}",
                        secondary = FormatterUtils.formatAmount(currencySymbol, tipAmount),
                    ),
                )
            }

            if (!isLoyaltyBalance) {
                add(
                    ReceiptPreviewLine(
                        primary = "-".repeat(PREVIEW_SMALL_FONT_LINE_WIDTH / 3),
                        alignment = TextAlign.End,
                    ),
                )

                if (partialApproval != null) {
                    add(ReceiptPreviewLine(
                        primary = resources.getString(R.string.receipt_original_amount),
                        secondary = partialApproval.originalAmount?.let { FormatterUtils.formatAmount(currencySymbol, it) } ?: "----",
                    ))
                    add(ReceiptPreviewLine(
                        primary = resources.getString(R.string.receipt_approved_amount),
                        secondary = FormatterUtils.formatAmount(currencySymbol, partialApproval.approvedAmount),
                        emphasis = true,
                        fontSize = ReceiptPreviewFontSize.LARGE,
                    ))
                } else totalAmount?.let {
                    add(
                        ReceiptPreviewLine(
                            primary = resources.getString(R.string.Total).uppercase(Locale.getDefault()),
                            secondary = FormatterUtils.formatAmount(currencySymbol, it),
                            emphasis = true,
                            fontSize = ReceiptPreviewFontSize.LARGE,
                        ),
                    )
                }
            }

            if (transaction.type != TransactionType.REFUND && transaction.returnStatus != ReturnStatus.Voided) {
                when (cvmPresentation.pinVerification) {
                    ReceiptPinVerification.ONLINE -> add(
                        ReceiptPreviewLine(primary = resources.getString(R.string.PinVerified), emphasis = true),
                    )
                    ReceiptPinVerification.OFFLINE -> add(
                        ReceiptPreviewLine(primary = resources.getString(R.string.PinVerifiedICC), emphasis = true),
                    )
                    ReceiptPinVerification.NONE -> Unit
                }

                if (cvmPresentation.noSignatureRequiredEmv) {
                    add(
                        ReceiptPreviewLine(
                            primary = resources.getString(
                                when {
                                    cvmPresentation.noSignatureRequiredCdcvm ->
                                        R.string.receipt_no_signature_required_cdcvm
                                    cvmPresentation.noSignatureRequiredCvm ->
                                        R.string.receipt_no_signature_required_cvm
                                    else -> R.string.receipt_no_signature_required_emv
                                },
                            ),
                            emphasis = true,
                            fontSize = ReceiptPreviewFontSize.TINY,
                        ),
                    )
                    add(
                        ReceiptPreviewLine(
                            primary = transaction.cardholderName.trim(),
                        ),
                    )
                } else if (signatureRequired && !hasSignature) {
                    add(ReceiptPreviewLine(primary = ""))
                    add(ReceiptPreviewLine(primary = ""))
                    val signatureLabel = resources.getString(R.string.receipt_signature)
                    val underline = "_".repeat((32 - signatureLabel.length).coerceAtLeast(4))
                    add(ReceiptPreviewLine(primary = signatureLabel + underline, alignment = TextAlign.Start))
                    add(
                        ReceiptPreviewLine(
                            primary = transaction.cardholderName.trim(),
                        ),
                    )

                    listOf(
                        resources.getString(R.string.receipt_agreement_1),
                        resources.getString(R.string.receipt_agreement_2),
                        resources.getString(R.string.receipt_agreement_3),
                    ).forEach { line ->
                        if (line.isNotBlank()) {
                            add(
                                ReceiptPreviewLine(
                                    primary = line.uppercase(Locale.getDefault()),
                                    fontSize = ReceiptPreviewFontSize.TINY,
                                ),
                            )
                        }
                    }
                } else if (signatureRequired) {
                    add(
                        ReceiptPreviewLine(
                            primary = transaction.cardholderName.trim(),
                        ),
                    )
                }
            }

            if (emvEntry) {
                val emvLabel = resources.getString(R.string.emv_information)
                val filler = "=".repeat(
                    ((PREVIEW_TINY_FONT_LINE_WIDTH - emvLabel.length).coerceAtLeast(0)) / 2,
                )
                add(
                    ReceiptPreviewLine(
                        primary = "$filler$emvLabel$filler",
                        fontSize = ReceiptPreviewFontSize.TINY,
                    ),
                )
                add(
                    ReceiptPreviewLine(
                        primary = "${resources.getString(R.string.receipt_emv_app_label)} ${transaction.applicationName.trim()}",
                        alignment = TextAlign.Start,
                        fontSize = ReceiptPreviewFontSize.TINY,
                    ),
                )
                add(
                    ReceiptPreviewLine(
                        primary = "${resources.getString(R.string.AID)} ${transaction.AID.trim()}",
                        alignment = TextAlign.Start,
                        fontSize = ReceiptPreviewFontSize.TINY,
                    ),
                )
                add(
                    ReceiptPreviewLine(
                        primary = "${resources.getString(R.string.TVR)} ${transaction.TVR}",
                        secondary = "${resources.getString(R.string.TSI)} ${transaction.TSI}",
                        fontSize = ReceiptPreviewFontSize.TINY,
                    ),
                )
                add(
                    ReceiptPreviewLine(
                        primary = "${resources.getString(R.string.AC)} ${transaction.AC}",
                        secondary = "${resources.getString(R.string.ARC)} ${transaction.ARC}",
                        fontSize = ReceiptPreviewFontSize.TINY,
                    ),
                )
                add(
                    ReceiptPreviewLine(
                        primary = "=".repeat(PREVIEW_TINY_FONT_LINE_WIDTH),
                        fontSize = ReceiptPreviewFontSize.TINY,
                    ),
                )
            }

            val copyLabel = if (recipient == MERCHANT) {
                resources.getString(R.string.merchant_copy)
            } else {
                resources.getString(R.string.customer_copy)
            }
            add(
                ReceiptPreviewLine(
                    primary = copyLabel,
                    fontSize = ReceiptPreviewFontSize.MIN,
                ),
            )

            addLine(
                resources.getString(R.string.powered_by_global_connect),
                fontSize = ReceiptPreviewFontSize.MIN,
            )

            val version = resources.getString(R.string.version)
            val appVersion = GlobalConnectPaymentApplication.instance.appVersion
            add(
                ReceiptPreviewLine(
                    primary = "$version: $appVersion",
                    fontSize = ReceiptPreviewFontSize.MIN,
                ),
            )
        }

        if (lines.isEmpty()) {
            return null
        }

        return ReceiptPreviewState(
            lines = lines,
            recipientLabel = "",
            transactionLabel = "",
            signature = signatureBitmap.takeIf { signatureRequired },
            signatureRequired = signatureRequired,
        )
    }

    private fun resolveEntryModeLabel(method: String): String {
        if (method.isBlank()) {
            return ""
        }
        val resources = GlobalConnectPaymentApplication.instance.resources
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

