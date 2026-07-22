package one.globalconnect.paymentapp.records

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.unit.dp
import one.globalconnect.paymentapp.transaction.ROWHEIGHT
import one.globalconnect.paymentapp.transaction.Transaction

@Composable
fun TransactionItem(
    transaction: Transaction,
    onEditPressed: (String) -> Unit,
) {
    Row {
        Column {
            Row(Modifier.clickable {
                onEditPressed(transaction.id.toString())
            }.fillMaxWidth(1f)) {
                TransactionRow(
                    transaction = transaction,
                    modifier =  Modifier.height(ROWHEIGHT.dp).weight(1f).fillMaxWidth(1f)
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "",
                    modifier = Modifier.align(CenterVertically).padding(16.dp)
                )
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()  //fill the max height
            .height(1.dp),
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
fun QuickTipTransactionItem(
    transaction: Transaction,
    onEditPressed: (Int) -> Unit,
) {
    Row {
        Column {
            Row(Modifier.clickable {
                onEditPressed(transaction.id)
            }.fillMaxWidth(1f)) {
                QuickTipTransactionRow(
                    transaction = transaction,
                    modifier =  Modifier.height(ROWHEIGHT.dp).weight(1f).fillMaxWidth(1f)
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "",
                    modifier = Modifier.align(CenterVertically).padding(16.dp)
                )
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()  //fill the max height
            .height(1.dp),
        color = MaterialTheme.colorScheme.outline
    )
}

