package one.globalconnect.paymentapp.transaction

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import one.globalconnect.paymentapp.AppViewModelProvider
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.navigation.TopBar
import one.globalconnect.paymentapp.records.BackButton
import one.globalconnect.paymentapp.ui.theme.color_black
import one.globalconnect.paymentapp.ui.theme.color_grey90
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import one.globalconnect.paymentapp.uicpos.pos.model.SysParam
import one.globalconnect.paymentapp.uicpos.pos.model.TransactionMode
import one.globalconnect.paymentapp.utils.SoundEffect
import one.globalconnect.paymentapp.utils.SoundManager
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun TipScreen(
    onConfirmPressed: (String) -> Unit,
    onStartPaymentPressed: (String, String, String) -> Unit,
    onBackButtonPressed: () -> Unit
) {
    val context = LocalContext.current

    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }

    val viewModel: TipViewModel = viewModel(factory = viewModelFactory)

    var screenToShow by rememberSaveable { mutableStateOf(if (SysParam.getInstance().OptionsEnabled) TipMenuScreens.MainScreen else TipMenuScreens.CustomScreen) }

    val transaction by viewModel.transaction.observeAsState(Transaction())
    val totalByTip by viewModel.subtotalPlusTip.observeAsState("0.00")
    val tip by viewModel.tip.observeAsState("0.00")
    val subtotal by viewModel.subtotal.observeAsState("0.00")

    when (screenToShow) {
        TipMenuScreens.MainScreen -> {
            Scaffold(
                topBar = {
                    TopBar(
                        title = null,
                        navigationIcon = {
                            if (SysParam.getInstance().transactionMode == TransactionMode.Cafe) {
                                TextButton(
                                    onClick = {
                                        SoundManager.play(SoundEffect.KEY_DELETE) // 🔊 Play Click Sound
                                        onBackButtonPressed()
                                    },
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
                            }
                        },
                        actions = null
                    )
                },
                bottomBar = {
                    Box(
                        modifier = Modifier
                            .height(64.dp)
                            .background(color_secondaryThree)
                            .fillMaxSize()){
                        Button(
                            modifier = Modifier
                                .fillMaxWidth(1f)
                                .padding(12.dp)
                                .height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = color_black, // Color de fondo del botón
                                contentColor = color_white, // Color del texto
                                disabledContainerColor = color_grey90
                            ),
                            onClick = {
                                SoundManager.play(SoundEffect.KEY_TICK) // 🔊 Play Click Sound
                                if (SysParam.getInstance().transactionMode == TransactionMode.Cafe) {
                                    onStartPaymentPressed(
                                        totalByTip,
                                        tip,
                                        transaction.type.toTransactionString()
                                    )
                                } else {
                                    onConfirmPressed(transaction.id.toString())
                                }

                            },
                        ) {
                            Text(textAlign = TextAlign.Center, text = stringResource(id = R.string.complete_payment) )
                        }
                    }
                }
            ) { contentPadding ->
                Column(
                    Modifier
                        .fillMaxWidth(1f)
                        .padding(contentPadding)
                ) {
                    Text(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally),
                        text = stringResource(id = R.string.add_tip),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 48.dp),
                        text = stringResource(id = R.string.tip_total_display, totalByTip),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Medium
                    )


                    Text(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(32.dp),
                        text = stringResource(
                            id = R.string.tip_amount_breakdown,
                            subtotal,
                            tip,
                            stringResource(id = R.string.tip_label)
                        ),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Normal
                    )

                    Spacer(Modifier.weight(1f))
                    TipMenu({ newAmount ->
                        viewModel.updateTip(newAmount)
                    }, subtotal, viewModel.tipArray,
                        {
                            viewModel.updateTip("0.00")
                            screenToShow = TipMenuScreens.CustomScreen
                        })

                }
            }
        }

        TipMenuScreens.CustomScreen -> {
            fun handleCompletePayment(playSound: Boolean = true) {
                if (playSound) {
                    SoundManager.play(SoundEffect.KEY_TICK)
                }
                if (SysParam.getInstance().transactionMode == TransactionMode.Cafe) {
                    onStartPaymentPressed(
                        totalByTip,
                        tip,
                        transaction.type.toTransactionString()
                    )
                } else {
                    onConfirmPressed(transaction.id.toString())
                }
            }

            Scaffold(
                topBar = {
                    TopBar(
                        title = null,
                        navigationIcon = {
                            if (SysParam.getInstance().OptionsEnabled) {
                                BackButton(
                                    onBackPressed = {
                                        SoundManager.play(SoundEffect.KEY_DELETE) // 🔊 Play Click Sound
                                        viewModel.updateTip("0.00")
                                        screenToShow = TipMenuScreens.MainScreen
                                    },
                                    text = stringResource(id = R.string.tip_options)
                                )
                            }
                        },
                        actions = null
                    )
                },
                bottomBar = {
                    Box(
                        modifier = Modifier
                            .height(64.dp)
                            .background(color_secondaryThree)
                            .fillMaxSize()){
                        Button(
                            modifier = Modifier
                                .fillMaxWidth(1f)
                                .padding(12.dp)
                                .height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = color_primaryBrand, // Color de fondo del botón
                                contentColor = color_secondaryFive, // Color del texto
                                disabledContainerColor = color_grey90
                            ),
                            onClick = { handleCompletePayment() },
                        ) {
                            Text(textAlign = TextAlign.Center, text = stringResource(id = R.string.complete_payment))
                        }
                    }
                }
            ) { contentPadding ->
                Column(
                    Modifier
                        .fillMaxWidth(1f)
                        .padding(contentPadding)
                        .fillMaxSize()
                ) {
                    Text(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally),
                        text = stringResource(id = R.string.add_custom_tip),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 24.dp, bottom = 24.dp),
                        text = "$${tip}",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Medium
                    )


                    Text(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 8.dp, bottom = 12.dp),
                        text = "$${totalByTip} total",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Normal
                    )

                    Column(
                        Modifier
                            .fillMaxWidth(1f)
                            .fillMaxSize()
                            .background(color_secondaryThree)
                    ){
                        Numpad(
                            onSelected = { pressedKey ->
                                val _tip = when {
                                    pressedKey == "C" -> tip.toBigDecimal()
                                        .divide(BigDecimal(10))
                                        .setScale(2, RoundingMode.DOWN)
                                        .toPlainString()

                                    pressedKey == " " -> tip // Ignore spaces

                                    tip.length > 11 -> tip // ✅ Prevent exceeding 11 digits

                                    else -> tip.toBigDecimal()
                                        .multiply(BigDecimal(10))
                                        .add(pressedKey.toBigDecimal().divide(BigDecimal(100)))
                                        .setScale(2, RoundingMode.HALF_DOWN)
                                        .toPlainString()
                                }
                                viewModel.updateTip(_tip)
                            },
                            showReset = true, // ✅ Enable Reset button
                            onEnterPressed = { handleCompletePayment() }
                        )
                    }
                }
            }
        }
    }


}

@Composable
fun TipMenu(
    setTipSize: (String) -> Unit,
    originalAmount: String,
    tipArray: DoubleArray,
    onCustomPressed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(1f)
            .wrapContentHeight()
            .background(color_secondaryThree)
            .padding(8.dp)
    ) {
        val options = listOf(
            doubleToPercentString(tipArray[0]),
            doubleToPercentString(tipArray[1]),
            doubleToPercentString(tipArray[2]),
            doubleToPercentString(tipArray[3]),
            stringResource(id = R.string.tip_custom),
        )

        var selected by rememberSaveable { mutableStateOf("No Selection") }

        val modifier: Modifier =
            Modifier
                .weight(1f)
                .padding(start = 2.dp, end = 2.dp, top = 4.dp)
                .height(64.dp)

        Column {
            Row {
                Key(modifier = modifier, text = options[0], onClick = {
                    selected = options[0]
                    setTipSize("${originalAmount.toDouble().times(tipArray[0])}")
                }, selectedTipOption = selected)
                Key(modifier = modifier, text = options[1], onClick = {
                    selected = options[1]
                    setTipSize("${originalAmount.toDouble().times(tipArray[1])}")
                }, selectedTipOption = selected)
            }
            Row {
                Key(modifier = modifier, text = options[2], onClick = {
                    SoundManager.play(SoundEffect.KEY_TICK) // 🔊 Play Click Sound
                    selected = options[2]
                    setTipSize("${originalAmount.toDouble().times(tipArray[2])}")
                }, selectedTipOption = selected)
                Key(modifier = modifier, text = options[3], onClick = {
                    SoundManager.play(SoundEffect.KEY_TICK) // 🔊 Play Click Sound
                    selected = options[3]
                    setTipSize("${originalAmount.toDouble().times(tipArray[3])}")
                }, selectedTipOption = selected)
            }

            Row {
                Key(modifier = modifier, text = options[4], onClick = onCustomPressed, selectedTipOption = selected)
            }
        }
    }
}

@Composable
fun Key(
    modifier: Modifier,
    text: String,
    onClick: () -> Unit,
    selectedTipOption: String
) {
    val containerColor =
        if (text == selectedTipOption) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.background
    val contentColor =
        if (text == selectedTipOption) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onBackground
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
    ) {
        Text(
            text = if (text == "0%") stringResource(id = R.string.no_tip) else text,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 18.sp
        )
    }
}


class TipOption(var text: String)

fun doubleToPercentString(double: Double): String {
    val percent = BigDecimal(double).times(BigDecimal(100)).setScale(0, RoundingMode.HALF_DOWN)
    return "${percent.toPlainString()}%"
}

enum class TipMenuScreens {
    MainScreen,
    CustomScreen
}