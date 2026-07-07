package com.uic.pinpad.protocol

object PINPADHex {
    fun ByteArray.toHex(): String = joinToString(separator = "") { "%02X".format(it) }
}
