package one.globalconnect.paymentapp.transaction

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.profile.RowDivider
import one.globalconnect.paymentapp.profile.SettingRowBoolean
import one.globalconnect.paymentapp.ui.theme.color_error
import one.globalconnect.paymentapp.ui.theme.color_grey90
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_success
import java.math.BigDecimal
import java.math.RoundingMode


/**
 * Sale screen. Takes in a click method
 */
@Composable
fun SaleScreen_Old(
    onChargeClick: (String, String) -> Unit,
    amountLabel: String,
    buttonLabel: String,
    transactionType : TransactionType
) {
    var saleInProgress by remember { mutableStateOf(false) }
    Scaffold(
        bottomBar = {
            Box(
                modifier = Modifier
                    .height(64.dp)
                    .background(color_secondaryThree)
                    .fillMaxSize()){}
        }
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding)) {

            var amount by rememberSaveable { mutableStateOf("0.00") }
            val dynamicFontSize = when {
                amount.length > 10 -> 42.sp
                amount.length > 8 -> 48.sp
                else -> 58.sp
            }
            val amountpaddingtop = when {
                dynamicFontSize == 58.sp -> 36.dp
                else -> 48.dp
            }

            Text(
                modifier = Modifier
                    .padding(top = 32.dp)
                    .align(CenterHorizontally),
                textAlign = TextAlign.Center,
                fontSize = 36.sp,
                text = amountLabel
            )
            Text(
                modifier = Modifier
                    .padding(top = amountpaddingtop)
                    .align(CenterHorizontally),
                textAlign = TextAlign.Center,
                fontSize = dynamicFontSize,
                text = "$${amount}"
            )
            Spacer(Modifier.weight(1f))
            Numpad(
                onSelected = { pressedKey ->
                    amount = when (pressedKey) {
                        "C" -> (amount.toBigDecimal().divide(BigDecimal(10))
                            .setScale(2, RoundingMode.DOWN).toPlainString())

                        "R" -> "0.00" // ✅ Reset the amount when "R" is pressed

                        " " -> amount

                        else -> if (amount.length > 11) {
                            amount
                        } else {
                            amount.toBigDecimal().multiply(BigDecimal(10))
                                .add(pressedKey.toBigDecimal().divide(BigDecimal(100)))
                                .setScale(2, RoundingMode.HALF_DOWN).toPlainString()
                        }
                    }
                },
                showReset = true // ✅ Enable Reset button
            )

            Box(
                contentAlignment = Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color_secondaryThree)

            ){
                Button(
                    modifier = Modifier
                        .fillMaxWidth(1f)
                        .padding(horizontal = 8.dp, vertical = 0.dp)
                        .height(55.dp),
                    onClick = {
                        if (!saleInProgress) {
                            saleInProgress = true
                            Log.d("SaleScreen", "Trigger sale button. TransactionType: $transactionType")
                            onChargeClick(amount, transactionType.toTransactionString())
                        }
                    },
                    enabled = amount != "0.00" && !saleInProgress,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color_primaryBrand,
                        contentColor = color_secondaryFive,
                        disabledContainerColor = color_grey90
                    ),
                ) {
                    Text(
                        text = buttonLabel,
                        fontSize = when {
                            buttonLabel.length > 15 -> 32.sp
                            buttonLabel.length > 10 -> 36.sp
                            else -> 42.sp
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }

        }
    }
}



