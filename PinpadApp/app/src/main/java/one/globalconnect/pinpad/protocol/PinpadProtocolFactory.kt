package one.globalconnect.pinpad.protocol

import one.globalconnect.pinpad.PinpadApplication
import one.globalconnect.pinpad.device.PinpadDeviceCommands
import one.globalconnect.pinpad.security.PinpadKeyLoadAuthorizer

object PinpadProtocolFactory {
    fun create(app: PinpadApplication, asyncResponseSender: (ByteArray) -> Unit = {}): PinpadProtocolHandler {
        lateinit var handler: LegacyPinpadProtocolHandler
        val sessionController = PINPADSessionController(
            deviceInfoProvider = app.deviceInfoProvider,
            commandDevice = PinpadDeviceCommands(app, app.deviceEngine),
            keyLoadAuthorizer = PinpadKeyLoadAuthorizer(app, app.deviceInfoProvider.modelName()),
            asyncResponseSender = { response -> handler.sendAsyncResponse(response) },
        )
        handler = LegacyPinpadProtocolHandler(
            parser = PINPADStreamParser(),
            sessionController = sessionController,
            asyncResponseSender = asyncResponseSender,
        )
        return handler
    }
}
