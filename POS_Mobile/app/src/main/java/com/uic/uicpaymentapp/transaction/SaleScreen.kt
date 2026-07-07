package com.uic.uicpaymentapp.transaction

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.profile.RowDivider
import com.uic.uicpaymentapp.profile.SettingRowBoolean
import com.uic.uicpaymentapp.ui.theme.color_secondaryThree
import com.uic.uicpaymentapp.utils.SoundEffect
import com.uic.uicpaymentapp.utils.SoundManager
import com.uic.uicpaymentapp.utils.hideSystemBarsForImmersive
import com.uic.uicpaymentapp.utils.rememberWindowInsetsController



/**
 * Sale screen. Takes in a click method
 */
@Composable
fun SaleScreen(
    onChargeClick: (String, String, String, String, String, String) -> Unit,
    transactionType: TransactionType,
    promptConfig: AmountPromptConfig,
    topContent: @Composable ColumnScope.() -> Unit = {},
    onBeforeCharge: (() -> Boolean)? = null,
    // Enables previews/tests to showcase a pre-filled amount without altering runtime defaults.
    initialBaseAmount: String = "0.00",
    collectSupplementaryValue: () -> String = { "" },
) {
    var saleInProgress by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        SoundManager.preload()
    }

    val stepSequence = remember(promptConfig) {
        buildList {
            if (promptConfig.askAmount) add("amount")
            if (promptConfig.askTax1) add("tax1")
            if (promptConfig.askTax2) add("tax2")
            if (promptConfig.askTip) add("tip")
            if (size > 1) add("confirmamount")
        }
    }
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var step by remember { mutableStateOf(stepSequence.getOrNull(stepIndex) ?: "none") }
    var baseAmountState by rememberSaveable(stateSaver = AmountEntryStateSaver) {
        val parsed = AmountEntryLogic.fromTransactionString(initialBaseAmount)
        mutableStateOf(if (parsed.isZero) AmountEntryState() else parsed)
    }
    var taxAmountState by rememberSaveable(stateSaver = AmountEntryStateSaver) {
        mutableStateOf(AmountEntryState())
    }
    var taxZeroPressed by rememberSaveable { mutableStateOf(false) }
    var tax2AmountState by rememberSaveable(stateSaver = AmountEntryStateSaver) {
        mutableStateOf(AmountEntryState())
    }
    var tipAmountState by rememberSaveable(stateSaver = AmountEntryStateSaver) {
        mutableStateOf(AmountEntryState())
    }
    var tipZeroPressed by rememberSaveable { mutableStateOf(false) }
    var totalAmount by rememberSaveable { mutableStateOf(initialBaseAmount) }

    LaunchedEffect(stepSequence) {
        if (stepSequence.isEmpty() && !saleInProgress) {
            saleInProgress = true
            onChargeClick(
                transactionType.toTransactionString(),
                baseAmountState.transactionValue,
                taxAmountState.transactionValue,
                tax2AmountState.transactionValue,
                tipAmountState.transactionValue,
                collectSupplementaryValue(),
            )
        } else if (stepSequence.isNotEmpty()) {
            stepIndex = 0
            step = stepSequence[0]
        }
    }
    Scaffold(
        bottomBar = {
            Box(
                modifier = Modifier
                    .height(15.dp)
                    .background(color_secondaryThree)
                    .fillMaxSize()
            ) {}
        }
    ) { contentPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val adjustedContentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            top = 0.dp,
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = contentPadding.calculateBottomPadding(),
        )
        val swDp = LocalConfiguration.current.smallestScreenWidthDp
        val isCompactScreen = swDp < 360   // N82/N6: ~314dp SW
        val isN62Screen = swDp >= 480      // N62: 480dp SW

        Column(
            modifier = Modifier
                .padding(adjustedContentPadding)
        ) {
            // Determine which step is active
            val currentAmountDisplay = when (step) {
                "confirmamount" -> totalAmount
                "amount" -> baseAmountState.displayValue
                "tax1" -> taxAmountState.displayValue
                "tax2" -> tax2AmountState.displayValue
                "tip" -> tipAmountState.displayValue
                else -> "0"
            }
            val currentAmountTransaction = when (step) {
                "confirmamount" -> totalAmount
                "amount" -> baseAmountState.transactionValue
                "tax1" -> taxAmountState.transactionValue
                "tax2" -> tax2AmountState.transactionValue
                "tip" -> tipAmountState.transactionValue
                else -> "0.00"
            }
            val dynamicFontSize = when {
                currentAmountDisplay.length > 10 -> 42.sp
                currentAmountDisplay.length > 8 -> 48.sp
                isN62Screen -> 42.sp
                isCompactScreen -> 48.sp
                else -> 58.sp
            }
            val amountPaddingTop = when {
                isN62Screen -> 6.dp
                isCompactScreen -> 12.dp
                dynamicFontSize >= 58.sp -> 36.dp
                else -> 48.dp
            }

            val transactionName = transactionType.toTransactionName()

            // Whether the Enter/OK button should be active
            val enterEnabled = !saleInProgress && when (step) {
                "tax1" -> (currentAmountTransaction != "0.00") || (promptConfig.tax1ZeroAmountAllowed && taxZeroPressed)
                "tip" -> (currentAmountTransaction != "0.00") || tipZeroPressed
                else -> currentAmountTransaction != "0.00"
            }

            topContent()

            // Transaction name title below the logo
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = when { isN62Screen -> 2.dp; isCompactScreen -> 4.dp; else -> 8.dp }),
                textAlign = TextAlign.Center,
                fontSize = when { isN62Screen -> 18.sp; isCompactScreen -> 20.sp; else -> 24.sp },
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                text = transactionType.toStringForUsers(),
            )

            val currentLabel = when (step) {
                "confirmamount" -> stringResource(id = R.string.msg_amount_prompt_total)
                "amount" -> if (promptConfig.askTax1 || promptConfig.askTax2 || promptConfig.askTip) {
                    stringResource(id = R.string.msg_amount_prompt_base)
                } else {
                    stringResource(
                        id = R.string.msg_amount_prompt,
                        transactionName
                    )
                }
                "tax1" -> stringResource(id = R.string.msg_amount_prompt_tax1)
                "tax2" -> stringResource(id = R.string.msg_amount_prompt_tax2)
                "tip" -> stringResource(id = R.string.msg_amount_prompt_tip)
                else -> stringResource(id = R.string.sale_no_amount_required)
            }

            // Display the correct label and amount entry field
            Text(
                modifier = Modifier
                    .padding(top = when { isN62Screen -> 6.dp; isCompactScreen -> 12.dp; else -> 32.dp })
                    .align(CenterHorizontally),
                textAlign = TextAlign.Center,
                fontSize = when { isN62Screen -> 24.sp; isCompactScreen -> 28.sp; else -> 36.sp },
                text = currentLabel
            )

            Text(
                modifier = Modifier
                    .padding(top = amountPaddingTop)
                    .align(CenterHorizontally),
                textAlign = TextAlign.Center,
                fontSize = dynamicFontSize,
                text = "${promptConfig.currencySymbol ?: ""}${currentAmountDisplay}"
            )

            Spacer(Modifier.weight(1f))

            fun handlePrimaryAction(playSound: Boolean = true) {
                if (!saleInProgress) {
                    if (playSound) {
                        SoundManager.play(SoundEffect.KEY_RETURN)
                    }

                    val isFinalStep = step == "confirmamount" || (step == "amount" && stepSequence.size <= 1)
                    if (isFinalStep) {
                        if (onBeforeCharge?.invoke() == false) {
                            return
                        }
                        // Execute transaction
                        saleInProgress = true
                        Log.d("SaleScreen", "Transaction Started: $transactionType, Amount: $totalAmount")
                        onChargeClick(
                            transactionType.toTransactionString(),
                            baseAmountState.transactionValue,
                            taxAmountState.transactionValue,
                            tax2AmountState.transactionValue,
                            tipAmountState.transactionValue,
                            collectSupplementaryValue(),
                        )
                    } else {
                        when (step) {
                            "amount" -> {
                                totalAmount = baseAmountState.transactionValue
                                stepIndex += 1
                                step = stepSequence.getOrNull(stepIndex) ?: "confirmamount"
                            }

                            "tax1" -> {
                                totalAmount = (totalAmount.toBigDecimal() + taxAmountState.transactionValue.toBigDecimal()).toPlainString()
                                stepIndex += 1
                                step = stepSequence.getOrNull(stepIndex) ?: "confirmamount"
                            }

                            "tax2" -> {
                                totalAmount = (totalAmount.toBigDecimal() + tax2AmountState.transactionValue.toBigDecimal()).toPlainString()
                                stepIndex += 1
                                step = stepSequence.getOrNull(stepIndex) ?: "confirmamount"
                            }

                            "tip" -> {
                                totalAmount = (totalAmount.toBigDecimal() + tipAmountState.transactionValue.toBigDecimal()).toPlainString()
                                stepIndex += 1
                                step = stepSequence.getOrNull(stepIndex) ?: "confirmamount"
                            }
                        }
                    }
                }
            }

            // Helper: get the current entry state for the active step
            fun currentEntryState(): AmountEntryState = when (step) {
                "amount" -> baseAmountState
                "tax1" -> taxAmountState
                "tax2" -> tax2AmountState
                "tip" -> tipAmountState
                else -> AmountEntryState()
            }

            fun updateCurrentEntryState(newState: AmountEntryState) {
                when (step) {
                    "amount" -> baseAmountState = newState
                    "tax1" -> taxAmountState = newState
                    "tax2" -> tax2AmountState = newState
                    "tip" -> tipAmountState = newState
                }
            }

            fun performFullReset() {
                baseAmountState = AmountEntryState()
                taxAmountState = AmountEntryState()
                tax2AmountState = AmountEntryState()
                tipAmountState = AmountEntryState()
                totalAmount = "0.00"
                stepIndex = 0
                step = stepSequence.getOrNull(stepIndex) ?: "none"
                taxZeroPressed = false
                tipZeroPressed = false
            }

            // Build percentage options based on current step
            val percentageOptions = when (step) {
                "tax1" -> promptConfig.tax1Percentages.map { p ->
                    PercentageOption(label = "$p%", percentage = p)
                }
                "tax2" -> promptConfig.tax2Percentages.map { p ->
                    PercentageOption(label = "$p%", percentage = p)
                }
                "tip" -> promptConfig.tipPercentages.map { p ->
                    PercentageOption(label = "$p%", percentage = p)
                }
                else -> emptyList()
            }

            // Numeric Keypad
            if (promptConfig.requiresAmountEntry) {
                if (step == "confirmamount") {
                    // Confirm step: read-only display, only reset allowed
                    AmountKeypad(
                        onDigit = { },
                        onDoubleZero = { },
                        onDecimalPoint = { },
                        onBackspace = { },
                        onReset = {
                            performFullReset()
                        },
                        onEnterPressed = { if (enterEnabled) handlePrimaryAction() },
                    )
                } else {
                    AmountKeypad(
                        onDigit = { digit ->
                            if (step == "tax1" && digit == "0") taxZeroPressed = true
                            if (step == "tip" && digit == "0") tipZeroPressed = true
                            updateCurrentEntryState(
                                AmountEntryLogic.appendDigit(
                                    currentEntryState(),
                                    digit.toInt()
                                )
                            )
                        },
                        onDoubleZero = {
                            updateCurrentEntryState(
                                AmountEntryLogic.appendDoubleZero(currentEntryState())
                            )
                        },
                        onDecimalPoint = {
                            updateCurrentEntryState(
                                AmountEntryLogic.enterDecimalMode(currentEntryState())
                            )
                        },
                        onBackspace = {
                            updateCurrentEntryState(
                                AmountEntryLogic.backspace(currentEntryState())
                            )
                        },
                        onReset = when (step) {
                            "amount", "none" -> null
                            else -> {
                                { performFullReset() }
                            }
                        },
                        percentageOptions = percentageOptions,
                        onPercentageSelected = if (percentageOptions.isNotEmpty()) { percentage ->
                            val baseCents = baseAmountState.transactionValue.toMinorUnits()
                            updateCurrentEntryState(
                                AmountEntryLogic.fromPercentage(baseCents, percentage)
                            )
                        } else null,
                        onEnterPressed = { if (enterEnabled) handlePrimaryAction() },
                    )
                }
            }
        }
    }
}

/**
 * Handles numeric keypad input.
 */
fun handleNumpadInput(currentValue: String, pressedKey: String): String {
    return when (pressedKey) {
        "C" -> formatCurrency(removeLeastSignificantDigit(currentValue))
        " " -> currentValue
        "R" -> ""
        else -> {
            val updated = appendDigit(currentValue, pressedKey)
            if (updated.length > MAX_DISPLAY_LENGTH) currentValue else updated
        }
    }
}

private const val MAX_DISPLAY_LENGTH = 12

private fun appendDigit(currentValue: String, pressedKey: String): String {
    val digit = pressedKey.singleOrNull()?.digitToIntOrNull() ?: return currentValue
    val currentMinorUnits = currentValue.toMinorUnits()
    val updatedMinorUnits = (currentMinorUnits * 10) + digit
    return formatCurrency(updatedMinorUnits)
}

private fun removeLeastSignificantDigit(currentValue: String): Long {
    val currentMinorUnits = currentValue.toMinorUnits()
    return currentMinorUnits / 10
}

private fun String.toMinorUnits(): Long {
    if (isEmpty()) {
        return 0L
    }
    val digitsOnly = buildString(length) {
        for (char in this@toMinorUnits) {
            if (char.isDigit()) {
                append(char)
            }
        }
    }
    if (digitsOnly.isEmpty()) {
        return 0L
    }
    return digitsOnly.toLongOrNull() ?: 0L
}

private fun formatCurrency(minorUnits: Long): String {
    val whole = minorUnits / 100
    val fractional = (minorUnits % 100).toInt()
    return buildString {
        append(whole)
        append('.')
        if (fractional < 10) {
            append('0')
        }
        append(fractional)
    }
}

fun handleNumpadInputOnFinalAmount(currentValue: String, pressedKey: String): String {
    return when (pressedKey) {
        "R" -> {
            ""
        }
        else ->
            currentValue
    }
}

@Composable
fun TransactionErrorDialog(onDismiss: () -> Unit, text: String) {
    val windowInsetsController = rememberWindowInsetsController()

    LaunchedEffect(windowInsetsController) {
        windowInsetsController?.hideSystemBarsForImmersive()
    }

    AlertDialog(
        title = {
            Text(text = stringResource(id = R.string.err_connection_failed_title))
        },
        text = {
            Text(text = text)
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    windowInsetsController?.hideSystemBarsForImmersive()
                    onDismiss()
                }
            ) {
                Text(stringResource(id = R.string.ok))
            }
        })
}

@Composable
fun ConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    label: String,
    text: String
) {
    val windowInsetsController = rememberWindowInsetsController()

    LaunchedEffect(windowInsetsController) {
        windowInsetsController?.hideSystemBarsForImmersive()
    }

    AlertDialog(
        title = {
            Text(text = label)
        },
        text = {
            Text(text = text)
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    windowInsetsController?.hideSystemBarsForImmersive()
                    onConfirm()
                }
            ) {
                Text(stringResource(id = R.string.ok))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    windowInsetsController?.hideSystemBarsForImmersive()
                    onDismiss()
                }
            ) {
                Text(stringResource(id = R.string.cancel))
            }
        },
        shape = RectangleShape
    )
}

@Composable
fun ApplicationSelectDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    label: String,
    applicationsList: List<String>
) {
    val windowInsetsController = rememberWindowInsetsController()

    LaunchedEffect(windowInsetsController) {
        windowInsetsController?.hideSystemBarsForImmersive()
    }

    var selectedOption by remember { mutableStateOf(ApplicationOption("No selection")) }

    AlertDialog(
        title = {
            Text(text = label)
        },
        text = {
            val list = applicationsList.map {
                ApplicationOption(it)
            }
            Column(
                Modifier
                    .padding(start = 8.dp, end = 8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    )
            ) {
                list.forEach { option ->
                    SettingRowBoolean(
                        onClick = {
                            selectedOption = option
                        },
                        text = option.text,
                        selected = selectedOption.text == option.text,
                        enabled = true
                    )
                    RowDivider(
                        Modifier
                            .fillMaxWidth(1f)
                            .padding(start = 4.dp)
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    windowInsetsController?.hideSystemBarsForImmersive()
                    onConfirm(selectedOption.text)
                },
                enabled = selectedOption.text != "No selection"
            ) {
                Text(stringResource(id = R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    windowInsetsController?.hideSystemBarsForImmersive()
                    onDismiss()
                }
            ) {
                Text(stringResource(id = R.string.cancel))
            }
        },
        shape = RectangleShape
    )
}
class ApplicationOption(val text: String)

@Preview(showBackground = true)
@Composable
fun PreviewSaleScreen() {
    SaleScreen(
        onChargeClick = { _, _, _, _, _, _ -> },
        transactionType = TransactionType.SALE, // Or your enum default
        promptConfig = AmountPromptConfig(
            askAmount = true,
            askTax1 = true,
            askTax2 = false,
            askTip = true,
            tax1ZeroAmountAllowed = false,
        ),
    )
}

@Preview(showBackground = true, name = "Sale Screen - Prefilled Amount")
@Composable
fun PreviewSaleScreenWithPrefilledAmount() {
    SaleScreen(
        onChargeClick = { _, _, _, _, _, _ -> },
        transactionType = TransactionType.SALE,
        promptConfig = AmountPromptConfig(
            askAmount = true,
            askTax1 = true,
            askTax2 = false,
            askTip = true,
            tax1ZeroAmountAllowed = false,
        ),
        initialBaseAmount = "125.00",
    )
}

@Preview(showSystemUi = true, name = "N62 (3.5\")",
    device = "spec:width=320px,height=480px,dpi=180,isRound=false")
@Composable
fun PreviewSaleScreenN62() {
    SaleScreen(
        onChargeClick = { _, _, _, _, _, _ -> },
        transactionType = TransactionType.SALE,
        promptConfig = AmountPromptConfig(
            askAmount = true,
            askTax1 = true,
            askTax2 = false,
            askTip = true,
            tax1ZeroAmountAllowed = false,
        ),
    )
}


