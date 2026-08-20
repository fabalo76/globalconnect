package one.globalconnect.keyinjection.protocol

/** Common key injection command types shared across protocols. */
enum class KeyInjectionCommand {
    DUKPT_INJECT,
    MK_INJECT,
    SERIAL_NUM_REQUEST,
    SERIAL_NUM_WRITE,
    ERASE_KEYS,
    KTK_ENCRYPTED_KEY_INJECT,
}
