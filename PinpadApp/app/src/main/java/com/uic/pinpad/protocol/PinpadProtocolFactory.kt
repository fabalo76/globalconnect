package com.uic.pinpad.protocol

import com.uic.pinpad.PinpadApplication
import com.uic.pinpad.device.PinpadDeviceCommands

object PinpadProtocolFactory {
    fun create(app: PinpadApplication, asyncResponseSender: (ByteArray) -> Unit = {}): PinpadProtocolHandler {
        lateinit var handler: LegacyPinpadProtocolHandler
        val sessionController = PINPADSessionController(
            deviceInfoProvider = app.deviceInfoProvider,
            commandDevice = PinpadDeviceCommands(app, app.deviceEngine),
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
