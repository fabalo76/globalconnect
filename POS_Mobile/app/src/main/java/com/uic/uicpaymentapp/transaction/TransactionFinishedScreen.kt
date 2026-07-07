package com.uic.uicpaymentapp.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uic.uicpaymentapp.AppViewModelProvider
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.ui.theme.color_black
import com.uic.uicpaymentapp.ui.theme.color_grey95
import com.uic.uicpaymentapp.ui.theme.color_primaryBrand
import com.uic.uicpaymentapp.ui.theme.color_secondaryFive
import com.uic.uicpaymentapp.ui.theme.color_success
import com.uic.uicpaymentapp.utils.SoundEffect
import com.uic.uicpaymentapp.utils.SoundManager
import kotlinx.coroutines.delay

@Composable
fun TransactionFinishedScreen(
    onNewSalePress: () -> Unit
) {
    val context = LocalContext.current
    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }
    val viewModel: FinishedPaymentViewModel = viewModel(factory = viewModelFactory)
    val transaction by viewModel.transaction.observeAsState(Transaction())
    val receiptPreviewState by viewModel.receiptPreviewState.collectAsState(initial = null)

    val transactionId = transaction.id
    var dismissed by remember(transactionId) { mutableStateOf(false) }

    LaunchedEffect(transactionId, dismissed) {
        if (transactionId != 0 && !dismissed) {
            delay(15_000)
            if (!dismissed) {
                dismissed = true
                onNewSalePress()
            }
        }
    }

    val amountText = transaction.totalAmount.takeIf { it.isNotBlank() }?.let { "$$it" }
    val subtitle = transaction.masked_cardNumber.takeIf { it.isNotBlank() }
        ?: transaction.cardRangeName.takeIf { it.isNotBlank() }

    Box(modifier = Modifier.fillMaxSize()) {
        TransactionStatusContainer(
            title = transaction.type.toTransactionName(),
            amountText = amountText,
            subtitle = subtitle,
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircleOutline,
                contentDescription = null,
                modifier = Modifier.size(90.dp),
                tint = color_success,
            )
            Text(
                text = if (transaction.type == TransactionType.REFUND) {
                    stringResource(id = R.string.refund_complete)
                } else {
                    stringResource(id = R.string.payment_complete)
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(id = R.string.thank_you_payment),
                fontSize = 16.sp,
                color = color_secondaryFive.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color_grey95,
                        contentColor = color_black,
                    ),
                    onClick = {
                        SoundManager.play(SoundEffect.KEY_TICK)
                        viewModel.printMerchantReceipt()
                    },
                ) {
                    Text(
                        textAlign = TextAlign.Center,
                        text = stringResource(id = R.string.merchant_receipt),
                    )
                }

                Button(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color_grey95,
                        contentColor = color_black,
                    ),
                    onClick = {
                        SoundManager.play(SoundEffect.KEY_TICK)
                        viewModel.printCustomerReceipt()
                    },
                ) {
                    Text(
                        textAlign = TextAlign.Center,
                        text = stringResource(id = R.string.customer_receipt),
                    )
                }
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = color_primaryBrand,
                    contentColor = color_black,
                ),
                onClick = {
                    if (!dismissed) {
                        dismissed = true
                        SoundManager.play(SoundEffect.KEY_TICK)
                        onNewSalePress()
                    }
                },
            ) {
                val label = if (transaction.type == TransactionType.REFUND) {
                    stringResource(id = R.string.return_sale_screen)
                } else {
                    stringResource(id = R.string.new_transaction)
                }
                Text(textAlign = TextAlign.Center, text = label)
            }
        }

        receiptPreviewState?.let { previewState ->
            ReceiptPrintPreview(
                state = previewState,
                onDismissed = { viewModel.dismissReceiptPreview() },
            )
        }
    }
}
