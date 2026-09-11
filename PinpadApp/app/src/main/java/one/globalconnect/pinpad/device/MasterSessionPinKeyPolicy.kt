package one.globalconnect.pinpad.device

internal sealed interface MasterSessionPinKeyAction {
    data object UseResidentPinKey : MasterSessionPinKeyAction
    data object LoadEncryptedSessionKey : MasterSessionPinKeyAction
    data class Reject(
        val responseCode: Char,
        val schemeMismatch: Boolean = false,
    ) : MasterSessionPinKeyAction
}

internal fun masterSessionPinKeyAction(
    keyUsage: String,
    sessionKey: String,
): MasterSessionPinKeyAction {
    return when (keyUsage) {
        "P0" -> {
            if (sessionKey.length in SESSION_KEY_HEX_LENGTHS && sessionKey.all { it == '0' }) {
                MasterSessionPinKeyAction.UseResidentPinKey
            } else {
                MasterSessionPinKeyAction.Reject(responseCode = '1', schemeMismatch = true)
            }
        }

        "K0" -> when {
            sessionKey.isBlank() -> MasterSessionPinKeyAction.Reject('5')
            sessionKey.all { it == '0' } -> {
                MasterSessionPinKeyAction.Reject(responseCode = '1', schemeMismatch = true)
            }
            sessionKey.length !in SESSION_KEY_HEX_LENGTHS || !sessionKey.all { it.isProtocolHexDigit() } -> {
                MasterSessionPinKeyAction.Reject('5')
            }
            else -> MasterSessionPinKeyAction.LoadEncryptedSessionKey
        }

        else -> MasterSessionPinKeyAction.Reject('A')
    }
}

private fun Char.isProtocolHexDigit(): Boolean {
    return this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'
}

private val SESSION_KEY_HEX_LENGTHS = setOf(16, 32, 48)
