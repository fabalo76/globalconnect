package com.uic.pinpad.model

import kotlinx.serialization.Serializable

@Serializable
data class PinpadCommandRequest(
    val commandId: String,
    val frameType: String,
    val payloadAscii: String = "",
    val rawHex: String = "",
)

@Serializable
data class PinpadCommandResponse(
    val commandId: String,
    val frameType: String,
    val payloadAscii: String = "",
    val closeSessionAfterAck: Boolean = true,
)
