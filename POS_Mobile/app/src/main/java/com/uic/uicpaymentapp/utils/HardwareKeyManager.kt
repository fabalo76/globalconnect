package com.uic.uicpaymentapp.utils

import android.view.KeyEvent
import java.util.ArrayDeque

/**
 * Centralized dispatcher that translates physical keypad events into
 * semantic commands that can be consumed from composables.
 *
 * Devices like the Nexgo N80 expose dedicated hardware keys (numeric
 * digits, enter, clear, function keys, etc.).  These keys do not trigger
 * the on-screen numpad buttons, so we capture them at the Activity level
 * and forward them to the currently active listener.
 */
object HardwareKeyManager {

    private val lock = Any()
    private val listeners = ArrayDeque<HardwareKeyListener>()

    /**
     * Registers a new [listener] that will receive hardware key commands.
     * The most recently registered listener receives priority.  When the
     * caller leaves the composition it must unregister the listener.
     */
    fun registerListener(listener: HardwareKeyListener) {
        synchronized(lock) {
            // Ensure the listener is not duplicated in the stack
            listeners.remove(listener)
            listeners.addLast(listener)
        }
    }

    /**
     * Unregisters a previously registered [listener].
     */
    fun unregisterListener(listener: HardwareKeyListener) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    /**
     * Attempts to dispatch the supplied [event] to the active listener.
     * Only ACTION_UP events are translated in order to avoid duplicate
     * notifications while the user holds a key pressed.
     *
     * @return `true` when the event was handled by a listener.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP) {
            return false
        }
        val command = event.toCommand() ?: return false
        val snapshot: List<HardwareKeyListener> = synchronized(lock) {
            if (listeners.isEmpty()) {
                emptyList()
            } else {
                listeners.toList()
            }
        }
        if (snapshot.isEmpty()) {
            return false
        }
        for (listener in snapshot.asReversed()) {
            if (listener.onHardwareKey(command)) {
                return true
            }
        }
        return false
    }

    private fun KeyEvent.toCommand(): HardwareKeyCommand? = when (keyCode) {
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> HardwareKeyCommand.Digit(0)
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> HardwareKeyCommand.Digit(1)
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> HardwareKeyCommand.Digit(2)
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> HardwareKeyCommand.Digit(3)
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> HardwareKeyCommand.Digit(4)
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> HardwareKeyCommand.Digit(5)
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> HardwareKeyCommand.Digit(6)
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> HardwareKeyCommand.Digit(7)
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> HardwareKeyCommand.Digit(8)
        KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> HardwareKeyCommand.Digit(9)
        KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_NUMPAD_DOT -> HardwareKeyCommand.DecimalPoint
        KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> HardwareKeyCommand.Backspace
        KeyEvent.KEYCODE_CLEAR -> HardwareKeyCommand.Reset
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> HardwareKeyCommand.Enter
        KeyEvent.KEYCODE_ESCAPE -> HardwareKeyCommand.Cancel
        KeyEvent.KEYCODE_F1 -> HardwareKeyCommand.Function(1)
        KeyEvent.KEYCODE_F2 -> HardwareKeyCommand.Function(2)
        else -> null
    }
}

/**
 * Listener that receives translated hardware key commands.
 */
fun interface HardwareKeyListener {
    fun onHardwareKey(command: HardwareKeyCommand): Boolean
}

/**
 * High level representation for hardware keypad keys so the UI layer can
 * react without dealing with Android's raw key codes.
 */
sealed interface HardwareKeyCommand {
    data class Digit(val value: Int) : HardwareKeyCommand
    data object DecimalPoint : HardwareKeyCommand
    data object Backspace : HardwareKeyCommand
    data object Reset : HardwareKeyCommand
    data object Enter : HardwareKeyCommand
    data object Cancel : HardwareKeyCommand
    data class Function(val index: Int) : HardwareKeyCommand
}

/**
 * Convenience type for function keys like F1/F2 present on the N80
 * physical keypad.
 */
sealed interface HardwareFunctionKey {
    data object F1 : HardwareFunctionKey
    data object F2 : HardwareFunctionKey
    data class Custom(val index: Int) : HardwareFunctionKey
}
