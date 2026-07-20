package one.globalconnect.xtmsagent

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import one.globalconnect.xtmsagent.mqtt.notifications.EXTRA_MESSAGE_TEXT

class OperatorMessageActivity : AppCompatActivity() {

    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showMessage(intent?.getStringExtra(EXTRA_MESSAGE_TEXT).orEmpty())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showMessage(intent.getStringExtra(EXTRA_MESSAGE_TEXT).orEmpty())
    }

    override fun onDestroy() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private fun showMessage(message: String) {
        if (message.isBlank()) {
            finish()
            return
        }

        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.msg_from_acquirer)
            .setMessage(message)
            .setPositiveButton(R.string.ok) { activeDialog, _ -> activeDialog.dismiss() }
            .create()
            .also { activeDialog ->
                activeDialog.setCancelable(false)
                activeDialog.setCanceledOnTouchOutside(false)
                activeDialog.setOnDismissListener { finish() }
                activeDialog.setOnShowListener {
                    activeDialog.findViewById<TextView>(android.R.id.message)
                        ?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    activeDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        ?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                }
                activeDialog.show()
            }
    }
}
