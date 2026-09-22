package one.globalconnect.paymentapp.transaction

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.ui.theme.*

internal object VoidPresentation { val active = MutableStateFlow(false) }

@Composable
internal fun VoidTransactionStatusScreen(transaction: Transaction, state: ReturnUiState, onDone: () -> Unit, ecrMode: Boolean) {
    val currency = GlobalConnectPaymentApplication.instance.tmsDatabase.Acquirer
        .firstOrNull { it.acquirer_id == transaction.acquirerId }?.currencySymbol.orEmpty()
    androidx.activity.compose.BackHandler { if (state is ReturnUiState.ResultReady) onDone() }
    TransactionStatusContainer(
        title = stringResource(R.string.trans_void),
        subtitle = transaction.masked_cardNumber,
        amountText = currency + transaction.totalAmount,
        attachToTop = true,
    ) {
        when (state) {
            is ReturnUiState.Loading -> {
                Spacer(Modifier.height(16.dp))
                if (state.processingStatus != null) {
                    ProcessingStatusList(state.processingStatus, textColor = color_secondaryFive,
                        secondaryTextColor = color_secondaryFive.copy(alpha = 0.8f))
                } else {
                    CircularProgressIndicator(color = color_primaryBrand)
                    Text(state.message, textAlign = TextAlign.Center, fontSize = 18.sp)
                }
            }
            is ReturnUiState.ResultReady -> {
                Icon(if (state.isSuccess) Icons.Filled.CheckCircleOutline else Icons.Filled.ErrorOutline,
                    contentDescription = null, modifier = Modifier.size(90.dp),
                    tint = if (state.isSuccess) color_success else color_alert)
                Text(stringResource(if (state.isSuccess) R.string.successful_void else R.string.void_failed_title),
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (!state.isSuccess) Text(state.message, textAlign = TextAlign.Center, fontSize = 16.sp)
                if (!ecrMode) {
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = color_primaryBrand, contentColor = color_black)) {
                        Text(stringResource(R.string.msg_done))
                    }
                }
            }
            else -> Unit
        }
    }
}
