package com.uic.uicpaymentapp.records

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uic.uicpaymentapp.AppViewModelProvider
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.ui.components.AcquirerSelectionOption
import com.uic.uicpaymentapp.ui.components.AcquirerSelectionPanel
import com.uic.uicpaymentapp.ui.preview.RecordsPreviewData
import com.uic.uicpaymentapp.ui.theme.color_primaryBrand
import com.uic.uicpaymentapp.ui.theme.color_black
import com.uic.uicpaymentapp.ui.theme.color_secondaryFive
import com.uic.uicpaymentapp.ui.theme.color_secondaryThree
import com.uic.uicpaymentapp.ui.theme.color_white
import com.uic.uicpaymentapp.utils.SoundEffect
import com.uic.uicpaymentapp.utils.SoundManager
import kotlinx.coroutines.delay

@Composable
fun TotalsReportScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }
    val viewModel: TotalsReportViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsState()
    val message = uiState.message
    val shouldNavigateBack = uiState.shouldNavigateBack

    LaunchedEffect(message) {
        if (message != null) {
            delay(3_000)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(shouldNavigateBack) {
        if (shouldNavigateBack) {
            onBack()
            viewModel.onNavigationHandled()
        }
    }

    val headerTitle = stringResource(id = R.string.totals_report)
    val selectionTitle = stringResource(id = R.string.sale_select_acquirer_title)
    val noAcquirersMessage = stringResource(id = R.string.totals_message_no_acquirers)
    val selectionOptions = buildList {
        add(
            AcquirerSelectionOption(
                id = "",
                title = stringResource(id = R.string.totals_option_all_acquirers),
            ),
        )
        uiState.acquirers.forEach { acquirer ->
            add(
                AcquirerSelectionOption(
                    id = acquirer.id,
                    title = acquirer.name,
                ),
            )
        }
    }
    val acquirerOptions = if (uiState.acquirers.isEmpty()) emptyList() else selectionOptions

    val onBackPressed = {
        SoundManager.play(SoundEffect.KEY_DELETE)
        onBack()
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .fillMaxWidth()
                    .background(color_white),
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .height(64.dp)
                    .fillMaxWidth()
                    .background(color_secondaryThree),
            )
        },
    ) { padding ->
        TotalsReportContent(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(color_secondaryThree),
            headerTitle = headerTitle,
            selectionTitle = selectionTitle,
            noAcquirersMessage = noAcquirersMessage,
            acquirerOptions = acquirerOptions,
            message = message,
            isPrinting = uiState.isPrinting,
            onOptionSelected = { optionId ->
                SoundManager.play(SoundEffect.KEY_TICK)
                val acquirerId = optionId.takeIf { it.isNotBlank() }
                viewModel.printTotals(context, acquirerId)
            },
            onBack = onBackPressed,
            onCancel = onBackPressed,
        )
    }
}

@Composable
private fun TotalsReportContent(
    headerTitle: String,
    selectionTitle: String,
    noAcquirersMessage: String,
    acquirerOptions: List<AcquirerSelectionOption>,
    message: UiMessage?,
    isPrinting: Boolean,
    modifier: Modifier = Modifier,
    onOptionSelected: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 24.dp)) {
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                onClick = onBack,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = color_primaryBrand,
                    contentColor = color_secondaryFive,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(id = R.string.back),
                        modifier = Modifier
                            .padding(start = 0.dp)
                            .size(36.dp),
                    )
                    Text(
                        text = headerTitle,
                        fontSize = if (headerTitle.length < 15) 36.sp else 30.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (acquirerOptions.isEmpty()) {
                Text(
                    text = noAcquirersMessage,
                    color = color_white,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                val panelHeightFraction = 0.85f
                AcquirerSelectionPanel(
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 24.dp)
                        .fillMaxWidth()
                        .fillMaxHeight(panelHeightFraction)
                        .align(Alignment.BottomCenter),
                    headerTitle = "",
                    title = selectionTitle,
                    options = acquirerOptions,
                    onOptionSelected = onOptionSelected,
                    onCancel = onCancel,
                    bottomSheetStyle = true,
                )
            }

            MessageBanner(
                message = message,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )

            if (isPrinting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(color_black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun MessageBanner(
    message: UiMessage?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        message?.let { uiMessage ->
            Surface(
                color = color_white,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = stringResource(
                        id = uiMessage.resId,
                        *uiMessage.args.toTypedArray(),
                    ),
                    modifier = Modifier.padding(12.dp),
                    color = color_secondaryFive,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TotalsReportScreenPreview() {
    TotalsReportContent(
        headerTitle = stringResource(id = R.string.totals_report),
        selectionTitle = stringResource(id = R.string.sale_select_acquirer_title),
        noAcquirersMessage = stringResource(id = R.string.totals_message_no_acquirers),
        acquirerOptions = RecordsPreviewData.acquirerOptions,
        message = RecordsPreviewData.totalsReportUiState.message,
        isPrinting = RecordsPreviewData.totalsReportUiState.isPrinting,
    )
}

@Preview(showBackground = true)
@Composable
private fun TotalsReportScreenPrintingPreview() {
    TotalsReportContent(
        headerTitle = stringResource(id = R.string.totals_report),
        selectionTitle = stringResource(id = R.string.sale_select_acquirer_title),
        noAcquirersMessage = stringResource(id = R.string.totals_message_no_acquirers),
        acquirerOptions = RecordsPreviewData.acquirerOptions,
        message = RecordsPreviewData.totalsPrintingUiState.message,
        isPrinting = RecordsPreviewData.totalsPrintingUiState.isPrinting,
    )
}

@Preview(showBackground = true)
@Composable
private fun TotalsReportScreenEmptyPreview() {
    TotalsReportContent(
        headerTitle = stringResource(id = R.string.totals_report),
        selectionTitle = stringResource(id = R.string.sale_select_acquirer_title),
        noAcquirersMessage = stringResource(id = R.string.totals_message_no_acquirers),
        acquirerOptions = emptyList(),
        message = null,
        isPrinting = false,
    )
}
