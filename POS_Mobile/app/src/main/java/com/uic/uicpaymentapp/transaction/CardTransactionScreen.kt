package com.uic.uicpaymentapp.transaction

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.uic.uicpaymentapp.AppViewModelProvider
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.cardreader.CardReaderStatus
import com.uic.uicpaymentapp.cardreader.CardReaderViewModel
import com.uic.uicpaymentapp.navigation.TopBar
import com.uic.uicpaymentapp.records.BackButton
import com.uic.uicpaymentapp.ui.components.AcquirerSelectionOption
import com.uic.uicpaymentapp.ui.components.AcquirerSelectionPanel
import com.uic.uicpaymentapp.ui.theme.color_alert
import com.uic.uicpaymentapp.ui.theme.color_black
import com.uic.uicpaymentapp.ui.theme.color_secondaryFive
import com.uic.uicpaymentapp.ui.theme.color_secondaryThree
import com.uic.uicpaymentapp.ui.theme.color_white
import com.uic.uicpaymentapp.transaction.ProcessingStatusList
import com.uic.uicpaymentapp.transaction.ProcessingStatusUi
import com.uic.uicpaymentapp.transaction.TransactionType
import com.uic.uicpaymentapp.transaction.isResultFinal

private const val TAG = "CardTransactionScreen"

@Composable
fun CardTransactionScreen(
    onNavigateToResult: (String, Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Log.d(TAG, "CardTransactionScreen composition start")
    val context = LocalContext.current
    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }
    val cardTransactionViewModel: CardTransactionViewModel = viewModel(factory = viewModelFactory)
    val cardReaderViewModel: CardReaderViewModel = viewModel()

    val uiState by cardTransactionViewModel.uiState.collectAsState()
    val cardReaderState by cardReaderViewModel.uiState.collectAsState()
    var pendingNavigation by remember { mutableStateOf<CardTransactionEvent.NavigateToResult?>(null) }
    Log.d(TAG, "CardTransactionScreen state step=${uiState.step} cardStatus=${cardReaderState.status}")

    val cancellationLocked = uiState.step is CardTransactionStep.ProcessingHost

    BackHandler {
        if (cancellationLocked) {
            Log.d(TAG, "BackHandler ignored while host processing")
        } else {
            Log.d(TAG, "BackHandler triggered")
            cardReaderViewModel.cancelCardSearch()
            cardTransactionViewModel.cancelTransaction()
        }
    }

    LaunchedEffect(Unit) {
        Log.d(TAG, "LaunchedEffect start transaction flow")
        cardTransactionViewModel.start()
    }

    LaunchedEffect(uiState.step) {
        Log.d(TAG, "LaunchedEffect step changed to ${uiState.step}")
        when (uiState.step) {
            is CardTransactionStep.AwaitingCard -> {
                Log.d(TAG, "Requesting card search amount=${uiState.totalAmount} country=${uiState.emvCountryCode} currency=${uiState.emvCurrencyCode}")
                cardReaderViewModel.startCardSearch(
                    amount = uiState.totalAmount,
                    cashbackAmount = "0.00",
                    countryCode = uiState.emvCountryCode,
                    currencyCode = uiState.emvCurrencyCode,
                )
            }
            else -> cardReaderViewModel.cancelCardSearch()
        }
    }

    LaunchedEffect(cardReaderState.cardData, uiState.step) {
        Log.d(
            TAG,
            "LaunchedEffect cardData updated present=${cardReaderState.cardData != null} step=${uiState.step}",
        )
        val cardData = cardReaderState.cardData
        if (cardData != null && uiState.step is CardTransactionStep.AwaitingCard) {
            Log.d(TAG, "Forwarding card data to CardTransactionViewModel")
            cardTransactionViewModel.onCardRead(cardData)
        }
    }

    val isResultFinal = uiState.processingStatus?.isResultFinal() == true
    val acknowledgeResult: () -> Unit = {
        pendingNavigation?.let { event ->
            val isRefund = cardTransactionViewModel.transactionType == TransactionType.REFUND
            onNavigateToResult(event.transactionId, isRefund)
            pendingNavigation = null
        }
    }

    LaunchedEffect(pendingNavigation, isResultFinal) {
        if (pendingNavigation != null && isResultFinal) {
            acknowledgeResult()
        }
    }

    LaunchedEffect(cardReaderState.status, uiState.step) {
        Log.d(
            TAG,
            "LaunchedEffect status change status=${cardReaderState.status} step=${uiState.step}",
        )
        if (uiState.step !is CardTransactionStep.AwaitingCard) return@LaunchedEffect
        when (val status = cardReaderState.status) {
            is CardReaderStatus.Error -> cardTransactionViewModel.onCardReadError(status.reason)
            CardReaderStatus.MultipleCards -> cardTransactionViewModel.onCardReadError(
                context.getString(R.string.card_reader_multiple_cards)
            )
            CardReaderStatus.SwipeIncorrect -> cardTransactionViewModel.onCardReadError(
                context.getString(R.string.card_reader_swipe_incorrect)
            )
            else -> Unit
        }
    }

    LaunchedEffect(cardTransactionViewModel) {
        Log.d(TAG, "LaunchedEffect events collector started")
        cardTransactionViewModel.events.collect { event ->
            Log.d(TAG, "CardTransactionScreen received event=$event")
            when (event) {
                is CardTransactionEvent.NavigateToResult -> {
                    Log.d(TAG, "Navigating to result ${event.transactionId}")
                    cardReaderViewModel.cancelCardSearch()
                    pendingNavigation = event
                }
                CardTransactionEvent.Cancelled -> {
                    Log.d(TAG, "CardTransactionEvent.Cancelled received")
                    cardReaderViewModel.cancelCardSearch()
                    onCancel()
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG, "CardTransactionScreen disposed cancelling search")
            cardReaderViewModel.cancelCardSearch()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopBar(
                title = {
                    Text(
                        text = cardTransactionViewModel.transactionType.toTransactionName(),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                    )
                },
                navigationIcon = if (cancellationLocked) null else {
                    {
                        BackButton(
                            onBackPressed = {
                                Log.d(TAG, "TopBar back pressed")
                                cardReaderViewModel.cancelCardSearch()
                                cardTransactionViewModel.cancelTransaction()
                            }
                        )
                    }
                },
                actions = null,
            )
        },
        bottomBar = {
            CardTransactionBottomBar(
                enabled = !cancellationLocked,
                onCancel = {
                    Log.d(TAG, "Cancel triggered from bottom bar scaffold")
                    cardReaderViewModel.cancelCardSearch()
                    cardTransactionViewModel.cancelTransaction()
                }
            )
        }
    ) { padding ->
        CardTransactionBody(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            uiState = uiState,
            cardReaderStatus = cardReaderState.status,
            onRetry = {
                Log.d(TAG, "Retry requested from CardTransactionBody")
                cardReaderViewModel.cancelCardSearch()
                cardTransactionViewModel.retry()
            },
            onSelectCurrency = {
                Log.d(TAG, "Currency selection requested $it")
                cardTransactionViewModel.onCurrencySelected(it)
            },
            onCancelCurrencySelection = {
                Log.d(TAG, "Currency selection cancelled")
                cardTransactionViewModel.cancelTransaction()
            },
            onSelectAcquirer = {
                Log.d(TAG, "Acquirer selection requested $it")
                cardTransactionViewModel.onAcquirerSelected(it)
            },
            onCancelAcquirerSelection = {
                Log.d(TAG, "Acquirer selection cancelled from SelectingAcquirerContent")
                cardReaderViewModel.cancelCardSearch()
                cardTransactionViewModel.cancelTransaction()
            }
        )
    }
}

@Composable
private fun CardTransactionBody(
    modifier: Modifier,
    contentPadding: PaddingValues,
    uiState: CardTransactionUiState,
    cardReaderStatus: CardReaderStatus,
    onRetry: () -> Unit,
    onSelectCurrency: (String) -> Unit,
    onCancelCurrencySelection: () -> Unit,
    onSelectAcquirer: (String) -> Unit,
    onCancelAcquirerSelection: () -> Unit,
) {
    Log.d(TAG, "CardTransactionBody step=${uiState.step} status=$cardReaderStatus")
    val amountText = uiState.totalAmount.takeIf { it.isNotBlank() }?.let { amt ->
        uiState.currencySymbol?.let { sym -> "$sym$amt" } ?: amt
    }

    when (val step = uiState.step) {
        is CardTransactionStep.SelectingCurrency -> TransactionStatusContainer(
            modifier = modifier,
            contentPadding = contentPadding,
            amountText = null,
            fullBleedContent = true,
        ) {
            SelectingCurrencyContent(
                statusMessage = uiState.statusMessage,
                currencies = step.currencies,
                onSelectCurrency = onSelectCurrency,
                onCancel = onCancelCurrencySelection,
            )
        }

        is CardTransactionStep.AwaitingCard -> TransactionStatusContainer(
            modifier = modifier,
            contentPadding = contentPadding,
            amountText = amountText,
        ) {
            AwaitingCardContent(
                statusMessage = uiState.statusMessage,
                cardReaderStatus = cardReaderStatus,
            )
        }

        is CardTransactionStep.SelectingAcquirer -> TransactionStatusContainer(
            modifier = modifier,
            contentPadding = contentPadding,
            amountText = null,
            fullBleedContent = true,
        ) {
            SelectingAcquirerContent(
                statusMessage = uiState.statusMessage,
                options = step.options,
                onSelectAcquirer = onSelectAcquirer,
                onCancel = onCancelAcquirerSelection,
            )
        }

        CardTransactionStep.ProcessingHost -> TransactionStatusContainer(
            modifier = modifier,
            contentPadding = contentPadding,
            amountText = amountText,
        ) {
            ProcessingHostContent(
                statusMessage = uiState.statusMessage,
                processingStatus = uiState.processingStatus,
            )
        }

        is CardTransactionStep.Error -> TransactionStatusContainer(
            modifier = modifier,
            contentPadding = contentPadding,
            amountText = amountText,
        ) {
            ErrorContent(
                message = step.message,
                onRetry = onRetry,
            )
        }

        CardTransactionStep.Initializing -> { /* transient — start() transitions away immediately */ }
    }
}

@Composable
private fun AwaitingCardContent(statusMessage: String?, cardReaderStatus: CardReaderStatus) {
    Log.d(TAG, "AwaitingCardContent statusMessage=$statusMessage status=$cardReaderStatus")
    val contextMessage = statusMessage ?: stringResource(R.string.sale_present_card)
    val animationRes = cardEntryAnimationRes(cardReaderStatus)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        animationRes?.let { CardEntryAnimation(animationRes = it) }
        Text(
            text = contextMessage,
            color = color_secondaryFive,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        cardReaderStatusMessage(cardReaderStatus)?.let { message ->
            Text(
                text = message,
                color = color_secondaryFive.copy(alpha = 0.85f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
        }
        CircularProgressIndicator(color = color_secondaryFive)
    }
}

@Composable
private fun SelectingCurrencyContent(
    statusMessage: String?,
    currencies: List<CurrencyInfo>,
    onSelectCurrency: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Log.d(TAG, "SelectingCurrencyContent currencies=${currencies.size}")
    val selectionOptions = currencies.map { currency ->
        AcquirerSelectionOption(
            id = currency.currencyCode.toString(),
            title = currency.nameResId?.let { stringResource(it) }
                ?: currency.symbol.ifBlank { currency.currencyCode.toString() },
        )
    }
    val title = statusMessage ?: stringResource(R.string.currency_select_title)
    AcquirerSelectionPanel(
        modifier = Modifier.fillMaxSize(),
        title = title,
        options = selectionOptions,
        onOptionSelected = {
            Log.d(TAG, "Selecting currency $it")
            onSelectCurrency(it)
        },
        onCancel = onCancel,
    )
}

@Composable
private fun SelectingAcquirerContent(
    statusMessage: String?,
    options: List<CardTransactionAcquirerOption>,
    onSelectAcquirer: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Log.d(TAG, "SelectingAcquirerContent options=${options.size}")
    val selectionOptions = options.map { option ->
        AcquirerSelectionOption(
            id = option.acquirer.AcqID,
            title = option.acquirer.AcquirerName,
        )
    }
    val title = statusMessage ?: stringResource(R.string.sale_select_acquirer_title)

    AcquirerSelectionPanel(
        modifier = Modifier.fillMaxSize(),
        title = title,
        options = selectionOptions,
        onOptionSelected = {
            Log.d(TAG, "Selecting acquirer $it")
            onSelectAcquirer(it)
        },
        onCancel = onCancel,
    )
}

@Composable
private fun ProcessingHostContent(
    statusMessage: String?,
    processingStatus: ProcessingStatusUi?,
) {
    Log.d(
        TAG,
        "ProcessingHostContent statusMessage=$statusMessage hasStatus=${processingStatus != null}",
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (processingStatus != null) {
            ProcessingStatusList(
                status = processingStatus,
                textColor = color_secondaryFive,
                secondaryTextColor = color_secondaryFive.copy(alpha = 0.8f),
            )
        } else {
            Text(
                text = statusMessage ?: stringResource(R.string.sale_host_processing),
                color = color_secondaryFive,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            )
            CircularProgressIndicator(color = color_secondaryFive)
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    Log.d(TAG, "ErrorContent message=$message")
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            color = color_alert,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Button(
            modifier = Modifier
                .height(48.dp)
                .fillMaxWidth(0.6f),
            onClick = {
                Log.d(TAG, "Retry tapped in ErrorContent")
                onRetry()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = color_secondaryFive,
                contentColor = color_white,
            )
        ) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun CardEntryAnimation(@DrawableRes animationRes: Int) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(animationRes)
            .crossfade(true)
            .build(),
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        contentScale = ContentScale.Fit,
    )
}

@DrawableRes
private fun cardEntryAnimationRes(status: CardReaderStatus): Int? {
    Log.d(TAG, "cardEntryAnimationRes status=$status")
    return when (status) {
        CardReaderStatus.Waiting -> R.drawable.card_anim_mcl
        CardReaderStatus.ProcessingEmv -> R.drawable.card_anim_c
        CardReaderStatus.SwipeIncorrect -> R.drawable.card_anim_mc
        CardReaderStatus.MultipleCards -> R.drawable.card_anim_cl
        CardReaderStatus.Success -> R.drawable.card_anim_m
        is CardReaderStatus.Error -> R.drawable.card_anim_m
        else -> R.drawable.card_anim_mcl
    }
}

@Composable
private fun CardTransactionBottomBar(
    enabled: Boolean,
    onCancel: () -> Unit,
) {
    Log.d(TAG, "CardTransactionBottomBar enabled=$enabled")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color_secondaryThree)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Button(
            modifier = Modifier.fillMaxWidth(0.6f),
            onClick = {
                Log.d(TAG, "Cancel tapped in bottom bar")
                onCancel()
            },
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = color_black,
                contentColor = color_white,
                disabledContainerColor = color_black.copy(alpha = 0.4f),
                disabledContentColor = color_white.copy(alpha = 0.6f),
            )
        ) {
            Text(text = stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun cardReaderStatusMessage(status: CardReaderStatus): String? {
    Log.d(TAG, "cardReaderStatusMessage status=$status")
    return when (status) {
        CardReaderStatus.Waiting -> stringResource(R.string.card_reader_waiting)
        CardReaderStatus.ProcessingEmv -> stringResource(R.string.card_reader_processing)
        CardReaderStatus.Success -> stringResource(R.string.card_reader_success)
        CardReaderStatus.MultipleCards -> stringResource(R.string.card_reader_multiple_cards)
        CardReaderStatus.SwipeIncorrect -> stringResource(R.string.card_reader_swipe_incorrect)
        is CardReaderStatus.Error -> stringResource(R.string.card_reader_error, status.reason)
        else -> null
    }
}
