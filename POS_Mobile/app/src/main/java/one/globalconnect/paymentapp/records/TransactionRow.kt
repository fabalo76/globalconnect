package one.globalconnect.paymentapp.records

import one.globalconnect.paymentapp.transaction.resolvedCardBrand
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.transaction.shortReportLabel
import one.globalconnect.paymentapp.transaction.TransactionType
import java.util.Locale


/**
 * Read-only item that displays generic information for a transaction
 *
 * @param transaction The transaction UI state that's represented by this item
 * @param modifier Modifier to be applied to the item
 */

@Composable
fun TransactionRow(
    transaction: Transaction,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier

    ) {
        Text(
            text = transaction.shortReportLabel(),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Start,
            modifier = Modifier.align(Alignment.CenterVertically)
                .padding(start = 8.dp)
                .width(40.dp)
        )

        CardIcon(
            _paymentNetworkString = transaction.resolvedCardBrand(),
            modifier = Modifier
                .align(CenterVertically)
                .padding(start = 8.dp, end = 12.dp)
        )
        Column(
            modifier = Modifier.weight(1f).padding(vertical = 8.dp)
        ) {
            val amt = if (transaction.type == TransactionType.REFUND) {
                "-$${transaction.totalAmount}"
            } else {
                "$${transaction.totalAmount}"
            }
            val color = if (transaction.type == TransactionType.REFUND) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onBackground
            }
            Text(
                text = amt,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
            Text(
                text = "${transaction.resolvedCardBrand()} ${transaction.masked_cardNumber.takeLast(4)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outlineVariant

            )
        }

        if (transaction.errorOnCapture) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = "QueryItemIcon",
                modifier = Modifier
                    .padding(end = 8.dp)
                    .align(CenterVertically)
                    .height(25.dp)
                    .width(25.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }

        Column(
            modifier = Modifier.align(CenterVertically).padding(start = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = transaction.formattedTime.ifBlank { defaultFormattedTime },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.batch_invoice_number, transaction.invoiceId),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun QuickTipTransactionRow(
    transaction: Transaction,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier

    ) {
        Text(
            text = "#${transaction.orderNo}",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Start,
            modifier = Modifier.align(Alignment.CenterVertically)
                .padding(start = 8.dp)
                .width(40.dp)
        )

        CardIcon(
            _paymentNetworkString = transaction.resolvedCardBrand(),
            modifier = Modifier
                .align(CenterVertically)
                .padding(start = 16.dp, end = 16.dp)
        )
        Column(
            modifier = Modifier
                .padding(top = 8.dp)
                .width(90.dp)
        ) {
            val amt = if (transaction.type == TransactionType.REFUND) {
                "-$${transaction.totalAmount}"
            } else {
                "$${transaction.totalAmount}"
            }
            val amtColor = if (transaction.tipAmount == "0.00") {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.primary
            }
            Text(
                text = amt,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = amtColor,
            )
            Text(
                text = transaction.formattedTime.ifBlank { defaultFormattedTime },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outlineVariant

            )
        }
        val tipColor = if (transaction.tipAmount == "0.00") {
            MaterialTheme.colorScheme.outlineVariant
        } else {
            MaterialTheme.colorScheme.primary
        }
        Text(
            text = stringResource(id = R.string.tip_amt, transaction.tipAmount),
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier
                .align(
                    CenterVertically
                ),
            color = tipColor
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
fun CardIcon(_paymentNetworkString: String,
             modifier: Modifier = Modifier) {
    val paymentNetworkString =
        _paymentNetworkString.uppercase(Locale.ENGLISH).filter { !it.isWhitespace() }
    if (paymentNetworkString.contains("MASTERCARD") || paymentNetworkString in setOf("MASTER", "MC")) {
        Image(
            painter = painterResource(id = R.drawable.mastercard_icon),
            contentDescription = "Mastercard icon",
            modifier = modifier.size(40.dp, 28.dp)
        )
    } else if (paymentNetworkString.contains("VISA")) {
        Image(
            painter = painterResource(id = R.drawable.visa_icon),
            contentDescription = "visa icon",
            modifier = modifier.size(40.dp, 28.dp)
        )
    } else if (paymentNetworkString.contains("UNIONPAY")) {
        Image(
            painter = painterResource(id = R.drawable.unionpay),
            contentDescription = "unionpay icon",
            modifier = modifier.size(40.dp, 28.dp),
        )
    } else if (paymentNetworkString.contains("DISCOVER")) {
        Image(
            painter = painterResource(id = R.drawable.discover),
            contentDescription = "discover icon",
            modifier = modifier.size(40.dp, 28.dp),
        )
    } else if (paymentNetworkString.contains("JCB")) {
        Image(
            painter = painterResource(id = R.drawable.jcb),
            contentDescription = "jcb icon",
            modifier = modifier.size(40.dp, 28.dp),
        )
    } else if (paymentNetworkString.contains("AMEX") || paymentNetworkString.contains("AMERICANEXPRESS")) {
        Image(
            painter = painterResource(id = R.drawable.amex_icon),
            contentDescription = "amex icon",
            modifier = modifier.size(40.dp, 28.dp),
        )
    } else if (paymentNetworkString.contains("CARTES-BANCAIRES")) {
        Image(
            painter = painterResource(id = R.drawable.cartes_bancaires),
            contentDescription = "cb icon",
            modifier = modifier.size(40.dp, 28.dp),
        )
    } else {
        Image(
            painter = painterResource(id = R.drawable.creditcarddark),
            contentDescription = "cc icon",
            modifier = modifier.size(40.dp, 28.dp),
        )
//        Icon(
//            imageVector = Icons.Default.CreditCard,
//            contentDescription = "default cc icon",
//            modifier = modifier.size(40.dp, 28.dp)
//        )
    }

}