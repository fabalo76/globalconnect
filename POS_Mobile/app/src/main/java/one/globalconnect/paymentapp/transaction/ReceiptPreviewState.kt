package one.globalconnect.paymentapp.transaction

import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign

/**
 * UI representation of a receipt preview that mimics the printed output.
 */
data class ReceiptPreviewState(
    val lines: List<ReceiptPreviewLine>,
    val recipientLabel: String,
    val transactionLabel: String,
    val signature: ImageBitmap?,
    val signatureRequired: Boolean,
    val triggerTimestamp: Long = System.currentTimeMillis(),
)

/**
 * Represents a single line within the receipt preview.
 */
data class ReceiptPreviewLine(
    val primary: String,
    val secondary: String? = null,
    val emphasis: Boolean = false,
    val alignment: TextAlign = TextAlign.Center,
)

@VisibleForTesting
internal fun emptyReceiptPreviewState(): ReceiptPreviewState =
    ReceiptPreviewState(
        lines = emptyList(),
        recipientLabel = "",
        transactionLabel = "",
        signature = null,
        signatureRequired = false,
    )
