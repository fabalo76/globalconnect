package one.globalconnect.pinpad.protocol

object PINPADControl {
    const val STX: Byte = 0x02
    const val ETX: Byte = 0x03
    const val ACK: Byte = 0x06
    const val SO: Byte = 0x0E
    const val SI: Byte = 0x0F
    const val NAK: Byte = 0x15
    const val SUB: Byte = 0x1A
    const val FS: Byte = 0x1C
    const val GS: Byte = 0x1D
    const val EOT: Byte = 0x04
}
