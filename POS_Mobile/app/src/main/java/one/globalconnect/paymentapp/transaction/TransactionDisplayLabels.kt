package one.globalconnect.paymentapp.transaction

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import one.globalconnect.paymentapp.R
import java.util.Locale

@Composable
fun cardEntryLabel(entry: String): String = stringResource(when (entry.uppercase(Locale.ROOT)) {
    "EMV", "EMV_CHIP", "CHIP" -> R.string.entry_chip_label
    "EMV_CONTACTLESS", "CONTACTLESS", "NFC", "RF" -> R.string.entry_contactless_label
    "SWIPE", "MSR" -> R.string.entry_swipe_label
    "FALLBACK_SWIPE", "FALLBACK" -> R.string.entry_fallback_label
    "MANUAL", "MANUALENTRY" -> R.string.entry_manual_label
    else -> R.string.card_reader_slot_unknown
})

@Composable
fun returnStatusLabel(status: ReturnStatus): String = when (status) {
    ReturnStatus.None -> ""
    ReturnStatus.Voided -> stringResource(R.string.transaction_status_voided)
    ReturnStatus.Refunded -> stringResource(R.string.transaction_status_refunded)
}
