package one.globalconnect.xtmsagent

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText

/** Keeps hardware Enter and the software keyboard actions on the same path. */
internal object PasswordDialogInput {
    fun bind(first: EditText, second: EditText, submit: () -> Unit) {
        bindAction(first, EditorInfo.IME_ACTION_NEXT) { second.requestFocus() }
        bindAction(second, EditorInfo.IME_ACTION_DONE, submit)
    }

    private fun bindAction(field: EditText, imeAction: Int, action: () -> Unit) {
        field.imeOptions = imeAction
        field.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            ) {
                // Consume both edges to prevent focus traversal and repeat submissions.
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) action()
                true
            } else {
                false
            }
        }
        field.setOnEditorActionListener { _, actionId, event ->
            if (event == null && actionId == imeAction) {
                action()
                true
            } else {
                false
            }
        }
    }
}
