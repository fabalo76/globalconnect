package one.globalconnect.paymentapp.transaction

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
import androidx.compose.runtime.rememberCoroutineScope
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
import one.globalconnect.paymentapp.AppViewModelProvider
import one.globalconnect.paymentapp.PendingUpdateManager
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.cardreader.CardReaderStatus
import one.globalconnect.paymentapp.cardreader.CardReaderViewModel
import one.globalconnect.paymentapp.cardreader.EmvApplicationSelection
import one.globalconnect.paymentapp.cardreader.nexgo.EmvTransactionPurpose
import one.globalconnect.paymentapp.navigation.TopBar
import one.globalconnect.paymentapp.records.BackButton
import one.globalconnect.paymentapp.ui.components.AcquirerSelectionOption
import one.globalconnect.paymentapp.ui.components.AcquirerSelectionPanel
import one.globalconnect.paymentapp.ui.theme.color_alert
import one.globalconnect.paymentapp.ui.theme.color_black
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import one.globalconnect.paymentapp.utils.SoundEffect
import one.globalconnect.paymentapp.utils.SoundManager
import one.globalconnect.paymentapp.transaction.ProcessingStatusList
import one.globalconnect.paymentapp.transaction.ProcessingStatusUi
import one.globalconnect.paymentapp.transaction.TransactionType
import one.globalconnect.paymentapp.transaction.isResultFinal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "CardTransactionScreen"
private const val EXPIRED_APPLICATION_WARNING_MS = 2_000L
private const val MOBILE_CVM_PROMPT_MS = 5_000L
private const val CONTACT_RETRY_PROMPT_MS = 5_000L

@Composable
fun CardTransactionScreen(
    onNavigateToResult: (String, TransactionType) -> Unit,
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
    val coroutineScope = rememberCoroutineScope()
    var pendingNavigation by remember { mutableStateOf<CardTransactionEvent.NavigateToResult?>(null) }
    var expiredApplicationWarningVisible by remember { mutableStateOf(false) }
    Log.d(TAG, "CardTransactionScreen state step=${uiState.step} cardStatus=${cardReaderState.status}")

    val startMobileCvmSecondTap: () -> Unit = {
        Log.i(TAG, "Continuing original mobile CVM transaction with contactless reader only")
        cardReaderViewModel.continueMobileCvmCardSearch()
    }
    val startContactRetry: () -> Unit = {
        Log.i(TAG, "Continuing issuer-directed retry with contact ICC only")
        cardTransactionViewModel.continueContactRetry()
    }

    val cancellationLocked = uiState.step is CardTransactionStep.ProcessingHost ||
        cardReaderState.cardRemovalRequired || expiredApplicationWarningVisible
    val decisionPending = uiState.step is CardTransactionStep.PartialApproval ||
        uiState.step is CardTransactionStep.PartialApprovalRemainder

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
                val transactionType = cardTransactionViewModel.transactionType
                val isPinMaintenance = OfflinePinChangeContract.isPinMaintenance(transactionType)
                Log.d(TAG, "Requesting card search amount=${uiState.totalAmount} country=${uiState.emvCountryCode} currency=${uiState.emvCurrencyCode}")
                cardReaderViewModel.startCardSearch(
                    amount = uiState.totalAmount,
                    invoiceNumber = cardTransactionViewModel.invoiceNumberForCardRead(),
                    cashbackAmount = "0.00",
                    countryCode = uiState.emvCountryCode,
                    currencyCode = uiState.emvCurrencyCode,
                    allowSwipe = !isPinMaintenance && !uiState.contactOnly,
                    allowContact = true,
                    allowContactless = cardTransactionViewModel.contactlessAllowed && !uiState.contactOnly,
                    purpose = when (transactionType) {
                        TransactionType.OFFLINE_PIN_CHANGE -> EmvTransactionPurpose.OFFLINE_PIN_CHANGE
                        TransactionType.PIN_UNBLOCK -> EmvTransactionPurpose.OFFLINE_PIN_UNBLOCK
                        else -> EmvTransactionPurpose.PAYMENT
                    },
                )
            }
            else -> {
                val shouldKeepKernelActive = cardReaderState.onlineAuthorizationPending &&
                    (uiState.step is CardTransactionStep.ProcessingHost ||
                        uiState.step is CardTransactionStep.SelectingAcquirer)
                if (shouldKeepKernelActive) {
                    Log.d(TAG, "Keeping EMV kernel active while host authorization is pending")
                } else {
                    cardReaderViewModel.cancelCardSearch()
                }
            }
        }
    }

    LaunchedEffect(cardReaderState.cardData, uiState.step) {
        Log.d(
            TAG,
            "LaunchedEffect cardData updated present=${cardReaderState.cardData != null} step=${uiState.step}",
        )
        val cardData = cardReaderState.cardData
        if (cardData != null && uiState.step is CardTransactionStep.AwaitingCard) {
            val onlineResponseHandler = if (cardReaderState.onlineAuthorizationPending) {
                cardReaderViewModel::completeOnlineAuthorization
            } else {
                null
            }
            val tvr = cardData.emvTags
                .firstOrNull { it.tag.equals("95", ignoreCase = true) }
                ?.value
            if (onlineResponseHandler != null && hasExpiredApplicationTvr(tvr)) {
                Log.i(TAG, "Expired application TVR detected; showing warning before online processing")
                expiredApplicationWarningVisible = true
                SoundManager.play(SoundEffect.KEY_INVALID)
                delay(EXPIRED_APPLICATION_WARNING_MS)
                expiredApplicationWarningVisible = false
            }
            Log.d(
                TAG,
                "Forwarding card data to CardTransactionViewModel " +
                    "onlineAuthorization=${onlineResponseHandler != null}",
            )
            cardTransactionViewModel.onCardRead(cardData, onlineResponseHandler)
        }
    }

    LaunchedEffect(cardReaderViewModel) {
        cardReaderViewModel.onlineCompletions.collect { completion ->
            Log.d(TAG, "Forwarding EMV kernel completion resultCode=${completion.resultCode}")
            cardTransactionViewModel.onEmvKernelCompleted(completion)
        }
    }

    val isResultFinal = uiState.processingStatus?.isResultFinal() == true
    val acknowledgeResult: () -> Unit = {
        pendingNavigation?.let { event ->
            onNavigateToResult(event.transactionId, cardTransactionViewModel.transactionType)
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
            is CardReaderStatus.Error -> {
                if (
                    ContactRetryContract.shouldRetryKernelResultUsingContact(
                        resultCode = status.resultCode,
                        cardSlot = status.cardSlot,
                    ) && !uiState.contactOnly
                ) {
                    cardTransactionViewModel.onKernelContactRetryRequired()
                } else if (cardTransactionViewModel.prepareAutomaticContactlessRetry(
                        status.resultCode,
                        status.cardSlot,
                        cardReaderState.onlineAuthorizationPending,
                    )) {
                    // The prompt step cancels any remaining search and restarts after its delay.
                } else {
                    cardTransactionViewModel.onCardReadError(status.reason)
                }
            }
            CardReaderStatus.MultipleCards -> cardTransactionViewModel.onCardReadError(
                context.getString(R.string.card_reader_multiple_cards)
            )
            CardReaderStatus.SwipeIncorrect -> cardTransactionViewModel.onCardReadError(
                context.getString(R.string.card_reader_swipe_incorrect)
            )
            else -> Unit
        }
    }

    LaunchedEffect(uiState.step) {
        if (uiState.step is CardTransactionStep.ContactlessReadRetryPrompt) {
            delay(3_000L)
            cardTransactionViewModel.continueAutomaticContactlessRetry()
        }
        if (uiState.step is CardTransactionStep.ContactRetryPrompt) {
            Log.i(TAG, "Contact ICC prompt displayed; auto-continuing in ${CONTACT_RETRY_PROMPT_MS}ms")
            cardReaderViewModel.beepContactCardRequired()
            delay(CONTACT_RETRY_PROMPT_MS)
            startContactRetry()
        }
    }

    LaunchedEffect(cardReaderState.status, uiState.step) {
        if (
            uiState.step is CardTransactionStep.AwaitingCard &&
            cardReaderState.status == CardReaderStatus.MobileCvmRequired
        ) {
            Log.i(TAG, "Mobile CVM prompt displayed; auto-continuing in ${MOBILE_CVM_PROMPT_MS}ms")
            cardReaderViewModel.beepMobileCvmInstruction()
            delay(MOBILE_CVM_PROMPT_MS)
            startMobileCvmSecondTap()
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
        PendingUpdateManager.isOperationInProgress = true
        onDispose {
            PendingUpdateManager.isOperationInProgress = false
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
            if (!decisionPending) {
                CardTransactionBottomBar(
                    enabled = !cancellationLocked,
                    onCancel = {
                        Log.d(TAG, "Cancel triggered from bottom bar scaffold")
                        cardReaderViewModel.cancelCardSearch()
                        cardTransactionViewModel.cancelTransaction()
                    }
                )
            }
        }
    ) { padding ->
        CardTransactionBody(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            uiState = uiState,
            cardReaderStatus = cardReaderState.status,
            cardRemovalRequired = cardReaderState.cardRemovalRequired,
            mobileCvmSecondTap = cardReaderState.mobileCvmSecondTap,
            expiredApplicationWarningVisible = expiredApplicationWarningVisible,
            pinMaintenanceType = cardTransactionViewModel.transactionType
                .takeIf(OfflinePinChangeContract::isPinMaintenance),
            applicationSelection = cardReaderState.applicationSelection,
            onContinueMobileCvm = startMobileCvmSecondTap,
            onContinueContactRetry = startContactRetry,
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
            },
            onSelectApplication = { selectedIndex ->
                Log.d(TAG, "EMV application selected index=$selectedIndex")
                cardReaderViewModel.selectApplication(selectedIndex)
            },
            onCancelApplicationSelection = {
                Log.d(TAG, "EMV application selection cancelled")
                cardReaderViewModel.cancelCardSearch()
                cardTransactionViewModel.cancelTransaction()
            },
            onAcceptPartialApproval = {
                coroutineScope.launch {
                    cardReaderViewModel.awaitContactCardRemoval()
                    cardTransactionViewModel.acceptPartialApproval()
                }
            },
            onDeclinePartialApproval = {
                cardTransactionViewModel.declinePartialApproval()
                coroutineScope.launch { cardReaderViewModel.awaitContactCardRemoval() }
            },
            onStartRemainingTransaction = {
                coroutineScope.launch {
                    cardReaderViewModel.awaitContactCardRemoval()
                    cardTransactionViewModel.startRemainingTransaction()
                }
            },
            onFinishAfterPartialApproval = cardTransactionViewModel::finishAfterPartialApproval,
        )
    }
}

@Composable
private fun CardTransactionBody(
    modifier: Modifier,
    contentPadding: PaddingValues,
    uiState: CardTransactionUiState,
    cardReaderStatus: CardReaderStatus,
    cardRemovalRequired: Boolean,
    mobileCvmSecondTap: Boolean,
    expiredApplicationWarningVisible: Boolean,
    pinMaintenanceType: TransactionType?,
    applicationSelection: EmvApplicationSelection?,
    onContinueMobileCvm: () -> Unit,
    onContinueContactRetry: () -> Unit,
    onRetry: () -> Unit,
    onSelectCurrency: (String) -> Unit,
    onCancelCurrencySelection: () -> Unit,
    onSelectAcquirer: (String) -> Unit,
    onCancelAcquirerSelection: () -> Unit,
    onSelectApplication: (Int) -> Unit,
    onCancelApplicationSelection: () -> Unit,
    onAcceptPartialApproval: () -> Unit,
    onDeclinePartialApproval: () -> Unit,
    onStartRemainingTransaction: () -> Unit,
    onFinishAfterPartialApproval: () -> Unit,
) {
    Log.d(TAG, "CardTransactionBody step=${uiState.step} status=$cardReaderStatus")
    if (cardRemovalRequired) {
        TransactionStatusContainer(
            modifier = modifier,
            contentPadding = contentPadding,
            amountText = null,
        ) {
            RemoveCardContent()
        }
        return
    }
    val amountText = uiState.totalAmount.takeIf { it.isNotBlank() }?.let { amt ->
        uiState.currencySymbol?.let { sym -> "$sym$amt" } ?: amt
    }
    if (expiredApplicationWarningVisible) {
        TransactionStatusContainer(
            modifier = modifier,
            contentPadding = contentPadding,
            amountText = amountText,
        ) {
            Text(
                text = stringResource(R.string.card_warning_expired),
                color = color_alert,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.card_warning_continuing_online),
                color = color_secondaryFive,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            )
            CircularProgressIndicator(color = color_secondaryFive)
        }
        return
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

        is CardTransactionStep.AwaitingCard -> {
            if (applicationSelection != null) {
                TransactionStatusContainer(
                    modifier = modifier,
                    contentPadding = contentPadding,
                    amountText = null,
                    fullBleedContent = true,
                ) {
                    SelectingApplicationContent(
                        selection = applicationSelection,
                        onSelectApplication = onSelectApplication,
                        onCancel = onCancelApplicationSelection,
                    )
                }
            } else {
                TransactionStatusContainer(
                    modifier = modifier,
                    contentPadding = contentPadding,
                    amountText = amountText,
                ) {
                    if (cardReaderStatus == CardReaderStatus.MobileCvmRequired) {
                        MobileCvmRequiredContent(onContinue = onContinueMobileCvm)
                    } else {
                        AwaitingCardContent(
                            statusMessage = uiState.statusMessage,
                            cardReaderStatus = cardReaderStatus,
                            pinMaintenanceType = pinMaintenanceType,
                            mobileCvmSecondTap = mobileCvmSecondTap,
                            contactOnly = uiState.contactOnly,
                        )
                    }
                }
            }
        }

        CardTransactionStep.ContactRetryPrompt -> TransactionStatusContainer(
            modifier = modifier,
            contentPadding = contentPadding,
            amountText = amountText,
        ) {
            ContactRetryRequiredContent(onContinue = onContinueContactRetry)
        }

        CardTransactionStep.ContactlessReadRetryPrompt -> TransactionStatusContainer(
            modifier = modifier,
            contentPadding = contentPadding,
            amountText = amountText,
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    uiState.statusMessage ?: stringResource(R.string.contactless_read_error),
                    color = color_secondaryFive,
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.contactless_read_retry),
                    color = color_secondaryFive,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                )
            }
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

        is CardTransactionStep.PartialApproval -> TransactionStatusContainer(
            modifier = modifier,
            contentPadding = contentPadding,
            amountText = null,
        ) {
            PartialApprovalContent(
                step = step,
                onAccept = onAcceptPartialApproval,
                onDecline = onDeclinePartialApproval,
            )
        }

        is CardTransactionStep.PartialApprovalRemainder -> TransactionStatusContainer(
            modifier = modifier,
            contentPadding = contentPadding,
            amountText = null,
        ) {
            PartialApprovalRemainderContent(
                step = step,
                onStartRemaining = onStartRemainingTransaction,
                onFinish = onFinishAfterPartialApproval,
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
private fun PartialApprovalContent(
    step: CardTransactionStep.PartialApproval,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.partial_approval_title),
            color = color_secondaryFive,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(
                R.string.partial_approve_amounts,
                step.requestedAmount,
                step.approvedAmount,
                step.remainingAmount,
            ),
            color = color_secondaryFive,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.partial_approval_accept_question),
            color = color_secondaryFive,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        DecisionButtons(
            positiveText = stringResource(R.string.partial_approval_accept),
            negativeText = stringResource(R.string.partial_approval_decline),
            onPositive = onAccept,
            onNegative = onDecline,
        )
    }
}

@Composable
private fun PartialApprovalRemainderContent(
    step: CardTransactionStep.PartialApprovalRemainder,
    onStartRemaining: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.partial_approval_accepted),
            color = color_secondaryFive,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.partial_approval_remaining_amount, step.remainingAmount),
            color = color_secondaryFive,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.partial_approval_remaining_question),
            color = color_secondaryFive,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        DecisionButtons(
            positiveText = stringResource(R.string.yes),
            negativeText = stringResource(R.string.no),
            onPositive = onStartRemaining,
            onNegative = onFinish,
        )
    }
}

@Composable
private fun DecisionButtons(
    positiveText: String,
    negativeText: String,
    onPositive: () -> Unit,
    onNegative: () -> Unit,
) {
    Button(
        modifier = Modifier.fillMaxWidth(0.78f),
        onClick = onPositive,
        colors = ButtonDefaults.buttonColors(
            containerColor = color_secondaryFive,
            contentColor = color_white,
        ),
    ) {
        Text(
            text = positiveText,
            textAlign = TextAlign.Center,
        )
    }
    Button(
        modifier = Modifier.fillMaxWidth(0.78f),
        onClick = onNegative,
        colors = ButtonDefaults.buttonColors(
            containerColor = color_black,
            contentColor = color_white,
        ),
    ) {
        Text(
            text = negativeText,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun RemoveCardContent() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CardEntryAnimation(animationRes = R.drawable.card_anim_c)
        Text(
            text = stringResource(R.string.card_reader_remove_card),
            color = color_secondaryFive,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.card_reader_remove_card_detail),
            color = color_secondaryFive.copy(alpha = 0.85f),
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
        )
        CircularProgressIndicator(color = color_secondaryFive)
    }
}

@Composable
private fun AwaitingCardContent(
    statusMessage: String?,
    cardReaderStatus: CardReaderStatus,
    pinMaintenanceType: TransactionType?,
    mobileCvmSecondTap: Boolean,
    contactOnly: Boolean,
) {
    Log.d(TAG, "AwaitingCardContent statusMessage=$statusMessage status=$cardReaderStatus")
    val contextMessage = when {
        mobileCvmSecondTap -> stringResource(R.string.mobile_cvm_tap_again)
        pinMaintenanceType == TransactionType.OFFLINE_PIN_CHANGE -> stringResource(R.string.offline_pin_change_insert_card)
        pinMaintenanceType == TransactionType.PIN_UNBLOCK -> stringResource(R.string.pin_unblock_insert_card)
        else -> statusMessage ?: stringResource(R.string.sale_present_card)
    }
    val animationRes = cardEntryAnimationRes(
        status = cardReaderStatus,
        contactOnly = contactOnly || pinMaintenanceType != null || mobileCvmSecondTap,
    )
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
private fun MobileCvmRequiredContent(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.mobile_cvm_follow_phone),
            color = color_secondaryFive,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.mobile_cvm_auto_continue),
            color = color_secondaryFive.copy(alpha = 0.85f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onContinue) {
            Text(text = stringResource(R.string.action_continue))
        }
    }
}

@Composable
private fun ContactRetryRequiredContent(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.contact_retry_required),
            color = color_secondaryFive,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.contact_retry_insert_chip),
            color = color_secondaryFive,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.contact_retry_auto_continue),
            color = color_secondaryFive.copy(alpha = 0.85f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onContinue) {
            Text(text = stringResource(R.string.action_continue))
        }
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
private fun SelectingApplicationContent(
    selection: EmvApplicationSelection,
    onSelectApplication: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    Log.d(TAG, "SelectingApplicationContent options=${selection.labels.size}")
    val selectionOptions = selection.labels.mapIndexed { index, label ->
        AcquirerSelectionOption(
            id = index.toString(),
            title = label,
        )
    }
    AcquirerSelectionPanel(
        modifier = Modifier.fillMaxSize(),
        title = stringResource(R.string.select_application_label),
        options = selectionOptions,
        onOptionSelected = { optionId ->
            optionId.toIntOrNull()?.let(onSelectApplication)
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
internal fun CardEntryAnimation(@DrawableRes animationRes: Int) {
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
private fun cardEntryAnimationRes(status: CardReaderStatus, contactOnly: Boolean): Int? {
    Log.d(TAG, "cardEntryAnimationRes status=$status contactOnly=$contactOnly")
    if (contactOnly) return R.drawable.card_anim_c
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
