package one.globalconnect.paymentapp.ui.preview

import one.globalconnect.paymentapp.parallel.echo.AcquirerOption
import one.globalconnect.paymentapp.parallel.echo.EchoTestStatus
import one.globalconnect.paymentapp.parallel.echo.EchoTestUiState
import one.globalconnect.paymentapp.transaction.ProcessingStatusStage
import one.globalconnect.paymentapp.transaction.ProcessingStatusStep
import one.globalconnect.paymentapp.transaction.ProcessingStatusStepState
import one.globalconnect.paymentapp.transaction.ProcessingStatusUi
import one.globalconnect.paymentapp.uicpos.pos.host.HostAddress
import one.globalconnect.paymentapp.uicpos.pos.host.HostEndpoint
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_HostConnectionInfo
import java.time.LocalDateTime

/**
 * Sample data used to render Compose previews for the echo test flow.
 */
object EchoTestPreviewData {

    private fun createAcquirerOption(
        id: String,
        name: String,
        primaryHost: String,
        secondaryHost: String = "",
    ): AcquirerOption {
        val acquirer = TMS_Acquirer(
            acquirer_id = id,
            acquirerName = name,
            terminalId = "TERM$id",
            merchantId = "MER$id",
            nii = 1,
            countryCode = 507,
            currencyCode = 840,
            currencySymbol = "USD",
            initialBatchNumber = 1,
            hostConnectionInfoRef = "IP$id",
            headerLine1 = "${name.uppercase()} 1",
            headerLine2 = "${name.uppercase()} 2",
            enableSale = true,
            enableCash = true,
            enablePayment = true,
            hostProtocol = "isswitch",
        )
        val ipProfile = TMS_HostConnectionInfo(
            config_id = "IP$id",
            primaryHost = primaryHost.substringBefore(":"),
            primaryPort = primaryHost.substringAfter(":", "443").toIntOrNull() ?: 443,
            primaryHostRetries = 1.0,
            secondaryHost = secondaryHost.substringBefore(":"),
            secondaryPort = secondaryHost.substringAfter(":", "443").toIntOrNull() ?: 443,
            secondaryHostRetries = 1.0,
            connectTimeoutSeconds = 30.0,
            ipAttempts = 1.0,
            transport = "tls",
            timeoutSeconds = 60.0,
        )
        return AcquirerOption(acquirer = acquirer, ipProfile = ipProfile)
    }

    private val acquirerOptions = listOf(
        createAcquirerOption("VENTAS", "Ventas", "primary.ventas.example.com:443", "backup.ventas.example.com:443"),
        createAcquirerOption("CLAVE", "Clave", "primary.clave.example.com:443"),
        createAcquirerOption("ALPHA", "Alpha", "primary.alpha.example.com:443"),
    )

    private val successProcessingStatus = ProcessingStatusUi(
        steps = listOf(
            ProcessingStatusStep(
                stage = ProcessingStatusStage.CONNECTING,
                title = "Connecting",
                status = ProcessingStatusStepState.COMPLETED,
                detail = "Connected to primary host",
            ),
            ProcessingStatusStep(
                stage = ProcessingStatusStage.SENDING,
                title = "Sending request",
                status = ProcessingStatusStepState.COMPLETED,
                detail = "ISO message sent",
            ),
            ProcessingStatusStep(
                stage = ProcessingStatusStage.WAITING_FOR_RESPONSE,
                title = "Waiting for response",
                status = ProcessingStatusStepState.COMPLETED,
                detail = "Response received",
            ),
            ProcessingStatusStep(
                stage = ProcessingStatusStage.PROCESSING_RESPONSE,
                title = "Processing response",
                status = ProcessingStatusStepState.COMPLETED,
                detail = "Parsing ISO message",
            ),
            ProcessingStatusStep(
                stage = ProcessingStatusStage.RESULT,
                title = "Result",
                status = ProcessingStatusStepState.COMPLETED,
                detail = "Approved (00)",
            ),
        ),
    )

    private val failureProcessingStatus = ProcessingStatusUi(
        steps = listOf(
            ProcessingStatusStep(
                stage = ProcessingStatusStage.CONNECTING,
                title = "Connecting",
                status = ProcessingStatusStepState.COMPLETED,
                detail = "Connected to primary host",
            ),
            ProcessingStatusStep(
                stage = ProcessingStatusStage.SENDING,
                title = "Sending request",
                status = ProcessingStatusStepState.COMPLETED,
                detail = "ISO message sent",
            ),
            ProcessingStatusStep(
                stage = ProcessingStatusStage.WAITING_FOR_RESPONSE,
                title = "Waiting for response",
                status = ProcessingStatusStepState.FAILED,
                detail = "Host timed out",
            ),
            ProcessingStatusStep(
                stage = ProcessingStatusStage.PROCESSING_RESPONSE,
                title = "Processing response",
                status = ProcessingStatusStepState.PENDING,
            ),
            ProcessingStatusStep(
                stage = ProcessingStatusStage.RESULT,
                title = "Result",
                status = ProcessingStatusStepState.FAILED,
                detail = "Unable to reach host",
            ),
        ),
    )

    val idleUiState = EchoTestUiState(
        acquirers = acquirerOptions,
        selectedAcquirerId = acquirerOptions.first().acquirer.AcqID,
        status = EchoTestStatus.Idle,
    )

    val successUiState = idleUiState.copy(
        status = EchoTestStatus.Success(
            responseCode = "00",
            endpoint = HostEndpoint(HostAddress("primary.ventas.example.com", 443), HostEndpoint.EndpointType.PRIMARY),
            roundTripMs = 842,
            timestamp = LocalDateTime.of(2024, 6, 12, 10, 30),
        ),
        processingStatus = successProcessingStatus,
        showStatusWindow = true,
    )

    val failureUiState = idleUiState.copy(
        status = EchoTestStatus.Failure("Unable to reach host"),
        processingStatus = failureProcessingStatus,
        showStatusWindow = true,
    )
}
