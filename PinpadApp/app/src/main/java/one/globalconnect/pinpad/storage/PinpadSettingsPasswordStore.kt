package one.globalconnect.pinpad.storage

import android.content.Context
import one.globalconnect.pinpad.security.PinpadSecurityConfigStore

class PinpadSettingsPasswordStore(context: Context) {
    private val securityStore = PinpadSecurityConfigStore(context)

    fun verify(scope: Scope, password1: String, password2: String): Boolean {
        val securityScope = when (scope) {
            Scope.ExitHome -> PinpadSecurityConfigStore.SettingsPasswordScope.ExitHome
            Scope.AndroidConfig -> PinpadSecurityConfigStore.SettingsPasswordScope.AndroidConfig
        }
        return securityStore.verifySettingsPasswords(securityScope, password1, password2)
    }

    enum class Scope {
        ExitHome,
        AndroidConfig,
    }
}
