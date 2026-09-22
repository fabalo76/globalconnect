package one.globalconnect.paymentapp.transaction

import one.globalconnect.paymentapp.transaction.resolvedCardBrand
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.navigation.TopBar
import one.globalconnect.paymentapp.records.CardIcon
import one.globalconnect.paymentapp.records.defaultFormattedVerboseDateTime
import one.globalconnect.paymentapp.ui.theme.color_black
import one.globalconnect.paymentapp.ui.theme.color_grey90
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun TipAdjustScreen(
    onBackButtonPressed: () -> Unit,
    transaction: Transaction,
    confirmTipAmount: (String) -> Unit,
    nextTransaction: ((Int) -> Unit)? = null,
) {
    var newTipAmount by rememberSaveable { mutableStateOf("0.00") }
    var showConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopBar(
                title = {
                    Text(
                        text = stringResource(R.string.trans_tip),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = onBackButtonPressed,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = color_white,
                            contentColor = color_black, // Color del texto
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Left"
                        )
                    }
                },
                actions = {
                    if (nextTransaction != null) {
                        TextButton(
                            modifier = Modifier.padding(16.dp),
                            onClick = {
                                newTipAmount = "0.00"
                                nextTransaction(transaction.id)
                            }) {
                            Text(text = stringResource(R.string.next), fontSize = 16.sp)
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowRight,
                                contentDescription = "Right"
                            )
                        }
                    }
                },
                modifier = Modifier.height(60.dp).padding(top = 20.dp)
            )
        },
    ) { contentPadding ->
        Column(
            Modifier
                .fillMaxSize(1f)
                .padding(contentPadding)
                .background(color_secondaryThree)
        ) {
            if (showConfirmationDialog) {
                ConfirmDialog(
                    onDismiss = {
                        showConfirmationDialog = false
                    },
                    onConfirm = {
                        confirmTipAmount(newTipAmount)
                        newTipAmount = "0.00"
                        showConfirmationDialog = false
                    },
                    text = stringResource(
                        id = R.string.confirm_tip_message,
                        newTipAmount,
                        transaction.orderNo
                    ),
                    label = stringResource(id = R.string.confirm_tip_label)
                )
            }
            Column(
                Modifier
                    .padding(8.dp,4.dp)
                    .background(color_white, shape = RoundedCornerShape(6.dp))
            ) {
                Row(
                    Modifier
                        .fillMaxWidth(1f)
                        .padding(top = 16.dp, bottom = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.details),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = transaction.formattedVerboseDateTime.ifBlank {
                            defaultFormattedVerboseDateTime
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()  //fill the max height
                        .height(1.dp),
                    color = MaterialTheme.colorScheme.outline
                )

                Row(
                    Modifier
                        .fillMaxWidth(1f)
                        .height(ROWHEIGHT.dp)
                ) {
                    CardIcon(
                        _paymentNetworkString = transaction.resolvedCardBrand(),
                        modifier = Modifier
                            .align(CenterVertically)
                            .padding(start = 16.dp, end = 16.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxHeight(1f), verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "${transaction.resolvedCardBrand()} ${transaction.masked_cardNumber.takeLast(4)}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = transaction.cardEntryMethod.CapitalizeFirstLowerRest(),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${stringResource(id = R.string.order)} ${transaction.orderNo}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.align(Alignment.CenterVertically)
                            .padding(end = 8.dp)
                            .width(100.dp)
                    )

                }
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()  //fill the max height
                        .height(1.dp),
                    color = MaterialTheme.colorScheme.outline
                )

                TotalWithAdjust(transaction, newTipAmount)
            }

            fun handleSetTip() {
                showConfirmationDialog = true
            }

            DialogNumpad(
                onSelected = { keyPressed ->
                    newTipAmount = adjustTip(newTipAmount, keyPressed)
                },
                showReset = false, // ✅ Disable Reset button (Set to false if not needed)
                onEnterPressed = { handleSetTip() },
                hideOnPhysicalKeypad = true,
            )
            Button(
                modifier = Modifier
                    .fillMaxWidth(1f)
                    .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                    .height(64.dp),
                onClick = { handleSetTip() },
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = color_primaryBrand, // BG Color
                    contentColor = color_secondaryFive, // Text Color
                    disabledContainerColor = color_grey90
                ),
            ) {
                Text(textAlign = TextAlign.Center, text = "${stringResource(id = R.string.set_tip)} $${newTipAmount}", fontSize = 16.sp)
            }
        }
    }
}

fun adjustTip(oldTipAmount: String, pressedKey: String): String {
    return when (pressedKey) {
        "C" -> (oldTipAmount.toBigDecimal().divide(BigDecimal(10))
            .setScale(2, RoundingMode.DOWN).toPlainString())

        " " -> oldTipAmount
        else ->
            oldTipAmount.toBigDecimal().multiply(BigDecimal(10))
                .add(pressedKey.toBigDecimal().divide(BigDecimal(100)))
                .setScale(2, RoundingMode.HALF_DOWN).toPlainString()
    }
}

@Composable
fun TotalWithAdjust(
    transaction: Transaction,
    newTipAmount: String
) {
    val padding = 16
    Row(Modifier.padding(start = padding.dp, end = padding.dp, top = 16.dp)) {
        Text(
            text = stringResource(id = R.string.subtotal_hyphen),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$${transaction.subTotal}",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
    Row(Modifier.padding(start = padding.dp, end = padding.dp, top = 8.dp)) {
        Text(text = stringResource(id = R.string.tip_hyphen), fontSize = 16.sp, color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.weight(1f))
        Text(
            text = if (newTipAmount == "0.00") "$${transaction.tipAmount}" else "$${newTipAmount}",
            fontSize = 16.sp,
            color = if (newTipAmount == "0.00") MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary
        )
    }
    Row(Modifier.padding(start = padding.dp, end = padding.dp, top = 8.dp)) {
        Text(
            text = stringResource(id = R.string.total_hyphen),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (newTipAmount == "0.00") "$${transaction.totalAmount}" else "$${
                stringAdd(
                    transaction.subTotal,
                    newTipAmount
                )
            }", fontSize = 16.sp, fontWeight = FontWeight.Bold
        )
    }
}

fun stringAdd(addendA: String, addendB: String): String {
    Log.d("StringAdd", "Addend A: ${addendA}")
    Log.d("StringAdd", "Addend B: ${addendB}")
    return try {
        addendA.toBigDecimal().add(addendB.toBigDecimal()).setScale(2, RoundingMode.HALF_UP)
            .toPlainString()
    } catch (e: Exception) {
        "0.00"
    }


}
