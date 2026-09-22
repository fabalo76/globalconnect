package one.globalconnect.paymentapp.parallel.echo

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.ui.components.AcquirerSelectionOption
import one.globalconnect.paymentapp.ui.components.AcquirerSelectionPanel
import one.globalconnect.paymentapp.transaction.ProcessingStatusList
import one.globalconnect.paymentapp.transaction.TransactionStatusContainer
import one.globalconnect.paymentapp.ui.preview.EchoTestPreviewData
import one.globalconnect.paymentapp.ui.theme.color_error
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import one.globalconnect.paymentapp.uicpos.pos.host.HostEndpoint

@Composable
fun EchoTestScreen(
    modifier: Modifier = Modifier,
    viewModel: EchoTestViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val cancelAction = remember(backDispatcher) {
        backDispatcher?.let { dispatcher ->
            { dispatcher.onBackPressed() }
        }
    }
    val acknowledgeResult: () -> Unit = remember(backDispatcher) {
        {
            val handled = backDispatcher?.let {
                it.onBackPressed()
                true
            } ?: false

            if (!handled) {
                viewModel.dismissStatusWindow()
            }
        }
    }

    EchoTestScreenContent(
        modifier = modifier,
        uiState = uiState,
        onSelectAcquirer = { acquirerId ->
            viewModel.selectAcquirer(acquirerId)
            viewModel.sendEchoTest()
        },
        onCancel = cancelAction,
        onDismissStatus = acknowledgeResult,
    )
}


@Composable
private fun EchoTestScreenContent(
    uiState: EchoTestUiState,
    onSelectAcquirer: (String) -> Unit,
    onCancel: (() -> Unit)?,
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusMessage = statusText(uiState)

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .fillMaxWidth()
                    .background(color_white)
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .height(0.dp)
                    .fillMaxWidth()
                    .background(color_secondaryThree)
            )
        }
    ) { contentPadding ->
        Box(
            modifier = modifier
                .padding(contentPadding)
                .fillMaxSize()
                .background(color_secondaryThree)
        ) {
            if (uiState.acquirers.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.echo_test_no_acquirers),
                        color = color_white,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                val acquirerOptions = uiState.acquirers.map { option ->
                    AcquirerSelectionOption(
                        id = option.acquirer.AcqID,
                        title = option.acquirer.AcquirerName,
                    )
                }

                AcquirerSelectionPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 0.dp),
                    headerTitle = stringResource(R.string.echo_test_title),
                    title = stringResource(R.string.echo_test_acquirer_title),
                    options = acquirerOptions,
                    onOptionSelected = onSelectAcquirer,
                    onCancel = onCancel,
                    bottomSheetStyle = true,
                )
            }

            if (uiState.showStatusWindow) {
                EchoTestStatusOverlay(
                    statusMessage = statusMessage,
                    uiState = uiState,
                    onDismiss = onDismissStatus
                )
            }
        }
    }
}

@Composable
private fun EchoTestStatusOverlay(
    statusMessage: String,
    uiState: EchoTestUiState,
    onDismiss: () -> Unit
) {
    TransactionStatusContainer(
        modifier = Modifier.fillMaxSize(),
        title = stringResource(R.string.echo_test_title),
        amountText = null,
        attachToTop = false,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            uiState.processingStatus?.let { status ->
                ProcessingStatusList(
                    status = status,
                    textColor = color_secondaryFive,
                    secondaryTextColor = color_secondaryFive.copy(alpha = 0.8f)
                )
            } ?: CircularProgressIndicator(color = color_secondaryFive)

            Text(
                text = statusMessage,
                color = if (uiState.status is EchoTestStatus.Failure) color_error else color_secondaryFive,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            if (uiState.status !is EchoTestStatus.InProgress) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color_primaryBrand,
                        contentColor = color_secondaryFive
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .height(56.dp)
                ) {
                    Text(
                        text = stringResource(R.string.ok),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = color_secondaryFive
                    )
                }
            }
        }
    }
}


@Composable
private fun statusText(state: EchoTestUiState): String {
    return when (val status = state.status) {
        EchoTestStatus.Idle -> stringResource(R.string.echo_test_status_idle)
        EchoTestStatus.InProgress -> stringResource(R.string.echo_test_status_running)
        is EchoTestStatus.Success -> {
            val endpoint = endpointLabel(status.endpoint.type)
            val responseCode = status.responseCode?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.echo_test_response_unknown)
            stringResource(
                R.string.echo_test_status_success,
                responseCode,
                endpoint,
                status.roundTripMs
            )
        }

        is EchoTestStatus.Failure -> stringResource(
            R.string.echo_test_status_failure,
            status.reason
        )
    }
}

@Composable
private fun endpointLabel(type: HostEndpoint.EndpointType): String {
    return when (type) {
        HostEndpoint.EndpointType.PRIMARY -> stringResource(R.string.echo_test_endpoint_primary)
        HostEndpoint.EndpointType.SECONDARY -> stringResource(R.string.echo_test_endpoint_secondary)
    }
}

@Preview(showBackground = true)
@Composable
private fun EchoTestScreenIdlePreview() {
    EchoTestScreenContent(
        uiState = EchoTestPreviewData.idleUiState,
        onSelectAcquirer = {},
        onCancel = {},
        onDismissStatus = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun EchoTestScreenSuccessPreview() {
    EchoTestScreenContent(
        uiState = EchoTestPreviewData.successUiState,
        onSelectAcquirer = {},
        onCancel = {},
        onDismissStatus = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun EchoTestStatusOverlayFailurePreview() {
    EchoTestStatusOverlay(
        statusMessage = statusText(EchoTestPreviewData.failureUiState),
        uiState = EchoTestPreviewData.failureUiState,
        onDismiss = {},
    )
}
