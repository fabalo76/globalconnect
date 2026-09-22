package one.globalconnect.pinpad.security

import android.content.Context
import android.os.Handler
import android.os.Looper
import one.globalconnect.pinpad.config.DeviceModelConfig
import one.globalconnect.pinpad.logging.PinpadTraceLog
import one.globalconnect.pinpad.protocol.PinpadKeypadKey
import one.globalconnect.pinpad.ui.PinpadDisplayController
import one.globalconnect.pinpad.ui.KeyLoadAuthenticationMessage

interface KeyLoadAuthorizer {
    fun requiresAuthorization(commandId: String): Boolean
    fun requestAuthorization(commandId: String, onResult: (Boolean) -> Unit): Boolean
    fun beginClearKeyInjectionMode(): Boolean
    fun isClearKeyInjectionModeActive(): Boolean
    fun recordClearKeyInjectionActivity()
    fun endClearKeyInjectionMode(reason: String): Boolean
    fun onKeypadKey(key: PinpadKeypadKey): Boolean
    fun cancel(): Boolean
}

class PinpadKeyLoadAuthorizer(
    context: Context,
    modelName: String,
) : KeyLoadAuthorizer {
    private val securityStore = PinpadSecurityConfigStore(context)
    private val useOnScreenKeypad = !DeviceModelConfig.hasPhysicalKeypad(modelName)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var pending: PendingAuthorization? = null
    @Volatile private var clearKeyInjectionModeActive = false
    private val clearKeyInjectionTimeout = Runnable {
        synchronized(lock) {
            if (!clearKeyInjectionModeActive) return@synchronized
            clearKeyInjectionModeActive = false
            PinpadTraceLog.device("clear-key injection mode closed reason=inactivity_timeout")
            PinpadDisplayController.showIdle()
        }
    }

    override fun requiresAuthorization(commandId: String): Boolean =
        commandId in SECRET_KEY_COMMANDS && securityStore.protectSecretKeyInjection()

    override fun beginClearKeyInjectionMode(): Boolean {
        synchronized(lock) {
            if (clearKeyInjectionModeActive) {
                scheduleClearKeyInjectionTimeoutLocked()
                PinpadDisplayController.showKeyInjectionMode()
                return true
            }
        }
        return requestAuthorization(CLEAR_KEY_COMMAND_ID) { authorized ->
            if (authorized) {
                synchronized(lock) {
                    clearKeyInjectionModeActive = true
                    scheduleClearKeyInjectionTimeoutLocked()
                }
                PinpadTraceLog.device("clear-key injection mode opened")
            }
        }
    }

    override fun isClearKeyInjectionModeActive(): Boolean = clearKeyInjectionModeActive

    override fun recordClearKeyInjectionActivity() {
        synchronized(lock) {
            if (clearKeyInjectionModeActive) {
                scheduleClearKeyInjectionTimeoutLocked()
            }
        }
    }

    override fun endClearKeyInjectionMode(reason: String): Boolean = synchronized(lock) {
        val wasActive = clearKeyInjectionModeActive
        val hadPendingAuthorization = pending != null
        clearKeyInjectionModeActive = false
        mainHandler.removeCallbacks(clearKeyInjectionTimeout)
        if (hadPendingAuthorization) {
            completeLocked(false, reason)
        } else if (wasActive) {
            PinpadDisplayController.showIdle()
        }
        if (wasActive || hadPendingAuthorization) {
            PinpadTraceLog.device("clear-key injection mode closed reason=$reason")
        }
        wasActive || hadPendingAuthorization
    }

    override fun requestAuthorization(commandId: String, onResult: (Boolean) -> Unit): Boolean {
        synchronized(lock) {
            if (pending != null) return false
            pending = PendingAuthorization(commandId = commandId, onResult = onResult)
            renderLocked()
        }
        PinpadTraceLog.device(
            "key-load authorization requested command=$commandId input=${if (useOnScreenKeypad) "onscreen" else "physical"}",
        )
        return true
    }

    override fun onKeypadKey(key: PinpadKeypadKey): Boolean {
        synchronized(lock) {
            val current = pending ?: return false
            when (key) {
                PinpadKeypadKey.Digit0,
                PinpadKeypadKey.Digit1,
                PinpadKeypadKey.Digit2,
                PinpadKeypadKey.Digit3,
                PinpadKeypadKey.Digit4,
                PinpadKeypadKey.Digit5,
                PinpadKeypadKey.Digit6,
                PinpadKeypadKey.Digit7,
                PinpadKeypadKey.Digit8,
                PinpadKeypadKey.Digit9 -> {
                    val digit = key.ordinal.toString()
                    if (current.activePassword == 1) {
                        current.password1 = (current.password1 + digit).take(PASSWORD_LENGTH)
                    } else {
                        current.password2 = (current.password2 + digit).take(PASSWORD_LENGTH)
                    }
                    renderLocked()
                }
                PinpadKeypadKey.Clear -> {
                    if (current.activePassword == 1) {
                        current.password1 = current.password1.dropLast(1)
                    } else {
                        current.password2 = current.password2.dropLast(1)
                    }
                    renderLocked()
                }
                PinpadKeypadKey.Enter -> {
                    if (current.activePassword == 1) {
                        if (current.password1.length == PASSWORD_LENGTH) {
                            current.activePassword = 2
                            renderLocked()
                        }
                    } else if (current.password2.length == PASSWORD_LENGTH) {
                        verifyLocked(current)
                    }
                }
                PinpadKeypadKey.Cancel -> completeLocked(false, "cancelled")
                else -> Unit
            }
            return true
        }
    }

    override fun cancel(): Boolean = synchronized(lock) {
        if (pending != null) {
            completeLocked(false, "cancelled")
            return true
        }
        if (!clearKeyInjectionModeActive) return false
        clearKeyInjectionModeActive = false
        mainHandler.removeCallbacks(clearKeyInjectionTimeout)
        PinpadDisplayController.showIdle()
        PinpadTraceLog.device("clear-key injection mode closed reason=cancelled")
        true
    }

    private fun verifyLocked(current: PendingAuthorization) {
        when (val result = securityStore.verifyKeyLoadPasswords(current.password1, current.password2)) {
            PinpadSecurityConfigStore.VerificationResult.Success ->
                completeLocked(true, "accepted")
            is PinpadSecurityConfigStore.VerificationResult.Rejected -> {
                current.password1 = ""
                current.password2 = ""
                current.activePassword = 1
                current.message = KeyLoadAuthenticationMessage.InvalidPassword(result.attemptsRemaining)
                renderLocked()
            }
            is PinpadSecurityConfigStore.VerificationResult.Cooldown -> {
                current.message = KeyLoadAuthenticationMessage.Cooldown(
                    (result.remainingMs / 1000L).coerceAtLeast(1L),
                )
                renderLocked()
            }
        }
    }

    private fun completeLocked(authorized: Boolean, result: String) {
        val current = pending ?: return
        pending = null
        PinpadTraceLog.device("key-load authorization command=${current.commandId} result=$result")
        current.onResult(authorized)
        if (clearKeyInjectionModeActive) {
            PinpadDisplayController.showKeyInjectionMode()
        } else {
            PinpadDisplayController.showIdle()
        }
    }

    private fun renderLocked() {
        val current = pending ?: return
        PinpadDisplayController.showKeyLoadAuthentication(
            commandId = current.commandId,
            password1Digits = current.password1.length,
            password2Digits = current.password2.length,
            activePassword = current.activePassword,
            useOnScreenKeypad = useOnScreenKeypad,
            message = current.message,
            onKey = ::onKeypadKey,
            onSubmitPasswords = { first, second ->
                synchronized(lock) {
                    // Ignore callbacks belonging to an authorization that has ended.
                    if (useOnScreenKeypad && pending === current &&
                        first.length == PASSWORD_LENGTH && second.length == PASSWORD_LENGTH &&
                        first.all { it in '0'..'9' } && second.all { it in '0'..'9' }) {
                        current.password1 = first
                        current.password2 = second
                        verifyLocked(current)
                    }
                }
            },
        )
    }

    private data class PendingAuthorization(
        val commandId: String,
        val onResult: (Boolean) -> Unit,
        var password1: String = "",
        var password2: String = "",
        var activePassword: Int = 1,
        var message: KeyLoadAuthenticationMessage? = null,
    )

    private fun scheduleClearKeyInjectionTimeoutLocked() {
        mainHandler.removeCallbacks(clearKeyInjectionTimeout)
        mainHandler.postDelayed(clearKeyInjectionTimeout, CLEAR_KEY_INJECTION_TIMEOUT_MS)
    }

    private companion object {
        private const val PASSWORD_LENGTH = 7
        private const val CLEAR_KEY_COMMAND_ID = "02"
        private const val CLEAR_KEY_INJECTION_TIMEOUT_MS = 60_000L
        private val SECRET_KEY_COMMANDS = setOf("20", "21")
    }
}
